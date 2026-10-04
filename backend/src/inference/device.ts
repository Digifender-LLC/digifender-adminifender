import type { AuthContext } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { v } from '../http/validate';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow } from '../sources/registry';
import { MAX_DEVICE_CHUNKS, inputDigest, knownServiceIds, loadBodyChunks, queueLocalStatement, resultStatement } from './job';
import { THERMAL_STATES, type Readiness, localReady } from './routing';
import { loadSettings } from './settings';
import { normalizeTriage, parseModelJson, validateTriage } from './triage-output';

/** Device leases are replay-safe: an expired lease is reclaimed with a new token and a late result is refused. */
export const DEVICE_LEASE_MS = 5 * 60 * 1000;
const MAX_OUTPUT_CHARS = 64 * 1024;

const readinessShape = {
  foreground: v.boolean(),
  charging: v.boolean(),
  batteryPercent: v.integer({ min: 0, max: 100 }),
  thermal: v.oneOf(THERMAL_STATES),
  /** Model ID from the pinned manifest the phone verified; null when no model is loaded. */
  modelId: v.nullable(v.string({ max: 100, pattern: /^[A-Za-z0-9._:/-]+$/ })),
  modelManifestHash: v.nullable(v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ })),
};

export const readinessBody = v.object(readinessShape);

async function recordReadiness(db: D1Database, deviceId: string, body: ReturnType<typeof readinessBody>, now: number): Promise<Readiness> {
  await db
    .prepare(
      `INSERT INTO device_readiness(device_id, reported_at, foreground, charging, battery_percent, thermal, model_id, manifest_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET reported_at = excluded.reported_at, foreground = excluded.foreground, charging = excluded.charging,
         battery_percent = excluded.battery_percent, thermal = excluded.thermal, model_id = excluded.model_id, manifest_hash = excluded.manifest_hash`,
    )
    .bind(deviceId, now, body.foreground ? 1 : 0, body.charging ? 1 : 0, body.batteryPercent, body.thermal, body.modelId, body.modelManifestHash)
    .run();
  return { reportedAt: now, foreground: body.foreground, charging: body.charging, batteryPercent: body.batteryPercent, thermal: body.thermal, modelId: body.modelId };
}

// POST /device-work/readiness — OS-reported capability; routing prefers a ready phone over cloud.
export async function reportReadiness(env: Env, now: number, body: ReturnType<typeof readinessBody>, auth: AuthContext) {
  const report = await recordReadiness(env.CONTROL_DB, auth.deviceId, body, now);
  return { ready: localReady(report, 'background', now) };
}

interface ClaimRow {
  id: string;
  job_id: string;
  source_id: string;
  source_revision: number;
  policy_revision: number;
  input_digest: string;
  task: string;
}

const CLAIMABLE = "route = 'local' AND (state = 'queued' OR (state = 'leased' AND lease_until < ?))";

/**
 * POST /device-work/claim — records readiness, then leases the oldest queued local task when the phone passes
 * the baseline gates. One task per device at a time. Work whose source or policy no longer allows it is
 * cancelled here instead of being handed out.
 */
export async function claimWork(env: Env, now: number, body: ReturnType<typeof readinessBody>, auth: AuthContext) {
  const db = env.CONTROL_DB;
  const report = await recordReadiness(db, auth.deviceId, body, now);
  if (!localReady(report, 'background', now)) return { work: null, reason: 'not_ready' };
  const busy = await db.prepare("SELECT 1 AS hit FROM inference_attempts WHERE device_id = ? AND state = 'leased' AND lease_until >= ?").bind(auth.deviceId, now).first();
  if (busy) return { work: null, reason: 'busy' };
  const settings = await loadSettings(db);

  for (let i = 0; i < 10; i += 1) {
    const token = crypto.randomUUID();
    const row = await db
      .prepare(
        `UPDATE inference_attempts SET state = 'leased', lease_token = ?, lease_until = ?, device_id = ?, model_id = ?
         WHERE id = (SELECT id FROM inference_attempts WHERE ${CLAIMABLE} ORDER BY created_at, rowid LIMIT 1) AND ${CLAIMABLE}
         RETURNING id, job_id, source_id, source_revision, policy_revision, input_digest, task`,
      )
      .bind(token, now + DEVICE_LEASE_MS, auth.deviceId, body.modelId, now, now)
      .first<ClaimRow>();
    if (!row) return { work: null, reason: 'no_work' };

    const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
    const loaded = source ? await loadBodyChunks(env, source, MAX_DEVICE_CHUNKS) : null;
    const allowed =
      source && loaded && source.revision === row.source_revision && source.state === 'ready' && source.admission === 'normal' && ['hybrid', 'local_only'].includes(source.privacy) && settings.inference.mode !== 'no_ai';
    const digest = allowed ? await inputDigest(source, row.task, loaded.chunks) : null;
    if (!allowed || digest !== row.input_digest) {
      await db.prepare("UPDATE inference_attempts SET state = 'cancelled', error_code = 'not_eligible', completed_at = ? WHERE id = ? AND lease_token = ?").bind(now, row.id, token).run();
      continue;
    }
    // Policy changes that still allow local work move the task to the current policy revision.
    if (row.policy_revision !== settings.revision) {
      await db.prepare('UPDATE inference_attempts SET policy_revision = ? WHERE id = ? AND lease_token = ?').bind(settings.revision, row.id, token).run();
    }
    return {
      work: {
        attemptId: row.id,
        jobId: row.job_id,
        sourceId: row.source_id,
        sourceRevision: row.source_revision,
        policyRevision: settings.revision,
        task: row.task,
        inputDigest: row.input_digest,
        chunkIds: loaded.chunks.map((c) => c.id),
        modelId: body.modelId,
        leaseToken: token,
        expiresAt: new Date(now + DEVICE_LEASE_MS).toISOString(),
      },
    };
  }
  return { work: null, reason: 'no_work' };
}

const leaseToken = v.uuid();
const leaseLost = () => new HttpError(409, 'lease_lost', 'This task is no longer assigned to this device.');

export const heartbeatBody = v.object({ leaseToken });

// POST /device-work/{attemptId}/heartbeat — extends only a live lease held by this device.
export async function heartbeat(env: Env, now: number, body: ReturnType<typeof heartbeatBody>, auth: AuthContext, attemptId: string) {
  const result = await env.CONTROL_DB.prepare("UPDATE inference_attempts SET lease_until = ? WHERE id = ? AND lease_token = ? AND device_id = ? AND state = 'leased' AND lease_until >= ?")
    .bind(now + DEVICE_LEASE_MS, attemptId, body.leaseToken, auth.deviceId, now)
    .run();
  if (result.meta.changes !== 1) throw leaseLost();
  return { attemptId, expiresAt: new Date(now + DEVICE_LEASE_MS).toISOString() };
}

export const resultBody = v.object({
  leaseToken,
  inputDigest: v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ }),
  policyRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }),
  outputJson: v.string({ max: MAX_OUTPUT_CHARS }),
  modelManifestHash: v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ }),
});

interface LeasedRow {
  id: string;
  job_id: string;
  model_id: string;
  policy_revision: number;
  input_digest: string;
  source_id: string;
  source_revision: number;
  repair: number;
  lease_token: string | null;
  device_id: string | null;
  state: string;
  lease_until: number | null;
}

/**
 * POST /device-work/{attemptId}/result — accepted only under the live lease, for the same input and policy.
 * The output passes the same validation as cloud output. One invalid output queues one repair task.
 */
export async function submitResult(env: Env, now: number, body: ReturnType<typeof resultBody>, auth: AuthContext, attemptId: string) {
  const db = env.CONTROL_DB;
  const attempt = await db
    .prepare('SELECT id, job_id, model_id, policy_revision, input_digest, source_id, source_revision, repair, lease_token, device_id, state, lease_until FROM inference_attempts WHERE id = ?')
    .bind(attemptId)
    .first<LeasedRow>();
  if (!attempt || attempt.state !== 'leased' || attempt.lease_token !== body.leaseToken || attempt.device_id !== auth.deviceId || (attempt.lease_until ?? 0) < now) throw leaseLost();
  if (body.inputDigest !== attempt.input_digest) throw new HttpError(409, 'input_changed', 'The source changed after this task was assigned.');
  const settings = await loadSettings(db);
  if (body.policyRevision !== attempt.policy_revision || body.policyRevision !== settings.revision) throw new HttpError(409, 'policy_changed', 'Inference settings changed. Claim the task again.');

  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(attempt.source_id).first<RegistryRow>();
  const loaded = source ? await loadBodyChunks(env, source, MAX_DEVICE_CHUNKS) : null;
  if (!source || !loaded) throw leaseLost();
  const parsed = parseModelJson(body.outputJson);
  const checked = parsed.ok ? validateTriage(normalizeTriage(parsed.value), new Map(loaded.chunks.map((c) => [c.id, c.text.length])), await knownServiceIds(db)) : parsed;
  const live = assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND lease_token = ? AND state = 'leased' AND lease_until >= ?)", attempt.id, body.leaseToken, now);
  const usage = JSON.stringify({ modelManifestHash: body.modelManifestHash });

  try {
    if (checked.ok) {
      const results = await db.batch([
        live,
        db.prepare("UPDATE inference_attempts SET state = 'succeeded', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attempt.id),
        await resultStatement(db, { id: attempt.id, jobId: attempt.job_id, route: 'local', modelId: attempt.model_id }, { id: source.id, revision: attempt.source_revision, privacy: source.privacy }, settings.revision, checked.value, now),
      ]);
      return { attemptId, state: (results[2]?.meta.changes ?? 0) === 1 ? 'succeeded' : 'discarded' };
    }
    const repair = attempt.repair === 0;
    await db.batch([
      live,
      db.prepare("UPDATE inference_attempts SET state = 'invalid_output', error_code = 'invalid_output', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attempt.id),
      ...(repair
        ? [queueLocalStatement(db, { id: crypto.randomUUID(), jobId: attempt.job_id, source, policyRevision: settings.revision, digest: attempt.input_digest, reason: 'schema_repair', repair: true }, now)]
        : []),
    ]);
    return { attemptId, state: 'invalid_output', errors: checked.errors, repairQueued: repair };
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    // The unique success index: another agent already committed the authoritative output for this job.
    if (error instanceof Error && /UNIQUE constraint failed/.test(error.message)) throw new HttpError(409, 'already_completed', 'Another result was already accepted for this task.');
    throw error;
  }
}
