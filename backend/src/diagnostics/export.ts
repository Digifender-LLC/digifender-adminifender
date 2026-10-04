import { authenticate } from '../auth/sessions';
import { RELEASE_CONTRACT } from '../contracts';
import type { Env } from '../env';
import { loadSettings } from '../inference/settings';

const DAY_MS = 24 * 60 * 60 * 1000;
const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

/** Count, median and maximum of durations in milliseconds; null when nothing was measured. */
export function summarize(durations: number[]): { count: number; p50Ms: number | null; maxMs: number | null } {
  if (durations.length === 0) return { count: 0, p50Ms: null, maxMs: null };
  const sorted = [...durations].sort((a, b) => a - b);
  return { count: sorted.length, p50Ms: sorted[Math.floor((sorted.length - 1) / 2)]!, maxMs: sorted[sorted.length - 1]! };
}

async function appliedMigrations(db: D1Database): Promise<string[] | null> {
  try {
    return (await db.prepare('SELECT name FROM d1_migrations ORDER BY id').all<{ name: string }>()).results.map((r) => r.name);
  } catch {
    return null;
  }
}

/**
 * GET /diagnostics — a content-free export for troubleshooting (lld.md#health): versions, migrations, model
 * identities, work counts, timing summaries and opaque IDs with fixed codes. It excludes source text,
 * subjects, email addresses, object keys, tokens and secrets.
 */
export async function getDiagnostics(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const since = now - DAY_MS;
  const settings = await loadSettings(db);
  const { results: jobs } = await db.prepare('SELECT kind, state, COUNT(*) AS n FROM jobs GROUP BY kind, state ORDER BY kind, state').all<{ kind: string; state: string; n: number }>();
  const { results: failures } = await db
    .prepare("SELECT id, kind, state, error_code, attempt FROM jobs WHERE state IN ('failed', 'dead') ORDER BY created_at DESC, rowid DESC LIMIT 20")
    .all<{ id: string; kind: string; state: string; error_code: string | null; attempt: number }>();
  const { results: attempts } = await db
    .prepare('SELECT route, state, dispatched_at, completed_at, error_code FROM inference_attempts WHERE created_at >= ?')
    .bind(since)
    .all<{ route: string; state: string; dispatched_at: number | null; completed_at: number | null; error_code: string | null }>();
  const { results: turns } = await db
    .prepare("SELECT created_at, completed_at FROM chat_turns WHERE created_at >= ? AND state = 'complete' AND completed_at IS NOT NULL")
    .bind(since)
    .all<{ created_at: number; completed_at: number }>();
  const uncertain = {
    inferenceAttempts: (await db.prepare("SELECT id FROM inference_attempts WHERE state = 'uncertain' ORDER BY created_at, rowid LIMIT 20").all<{ id: string }>()).results.map((r) => r.id),
    operations: (await db.prepare("SELECT id FROM operations WHERE state = 'uncertain' ORDER BY created_at, rowid LIMIT 20").all<{ id: string }>()).results.map((r) => r.id),
    notifications: (await db.prepare("SELECT id FROM notification_events WHERE state = 'uncertain' ORDER BY created_at, rowid LIMIT 20").all<{ id: string }>()).results.map((r) => r.id),
  };
  const { results: models } = await db
    .prepare('SELECT r.device_id, r.model_id, r.manifest_hash, r.reported_at FROM device_readiness r JOIN devices d ON d.id = r.device_id WHERE d.revoked_at IS NULL ORDER BY r.device_id')
    .all<{ device_id: string; model_id: string | null; manifest_hash: string | null; reported_at: number }>();
  const { results: apps } = await db
    .prepare('SELECT id, push_app_version FROM devices WHERE revoked_at IS NULL AND push_app_version IS NOT NULL ORDER BY id')
    .all<{ id: string; push_app_version: string }>();

  const timed = (route: string) => summarize(attempts.filter((a) => a.route === route && a.dispatched_at !== null && a.completed_at !== null).map((a) => a.completed_at! - a.dispatched_at!));
  return {
    generatedAt: iso(now),
    release: RELEASE_CONTRACT,
    migrations: { control: await appliedMigrations(db) },
    settings: { revision: settings.revision, policyRevision: settings.policyRevision, inferenceMode: settings.inference.mode },
    models: {
      cloud: settings.inference.cloud.modelId,
      devices: models.map((m) => ({ deviceId: m.device_id, modelId: m.model_id, manifestHash: m.manifest_hash, reportedAt: iso(m.reported_at) })),
    },
    apps: apps.map((a) => ({ deviceId: a.id, appVersion: a.push_app_version })),
    jobs: jobs.map((j) => ({ kind: j.kind, state: j.state, count: j.n })),
    recentFailures: failures.map((f) => ({ jobId: f.id, kind: f.kind, state: f.state, errorCode: f.error_code, attempts: f.attempt })),
    timings: { lastDay: { cloudInference: timed('cloud'), localInference: timed('local'), chatTurns: summarize(turns.map((t) => t.completed_at - t.created_at)) } },
    uncertain,
  };
}
