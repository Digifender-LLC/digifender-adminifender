import { authenticate } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';
import { visibleSource } from '../sources/registry';
import { type Reservation, budgetView, settleStatements } from './budget';
import { INFER_TASK } from './job';
import { loadSettings } from './settings';

interface AttemptRow {
  id: string;
  route: string;
  model_id: string;
  policy_revision: number;
  state: string;
  reason: string;
  repair: number;
  error_code: string | null;
  usage_json: string | null;
  reservation_json: string;
  dispatched_at: number | null;
  completed_at: number | null;
  created_at: number;
}

const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

function attemptView(row: AttemptRow) {
  const reservation = row.route === 'cloud' ? (JSON.parse(row.reservation_json) as Partial<Reservation>) : null;
  return {
    id: row.id,
    route: row.route,
    modelId: row.model_id,
    policyRevision: row.policy_revision,
    state: row.state,
    reason: row.reason,
    repair: row.repair === 1,
    errorCode: row.error_code,
    reservedNeurons: reservation?.neurons ?? null,
    usage: row.usage_json ? (JSON.parse(row.usage_json) as Record<string, unknown>) : null,
    createdAt: iso(row.created_at),
    dispatchedAt: iso(row.dispatched_at),
    completedAt: iso(row.completed_at),
  };
}

/**
 * GET /sources/{id}/inference — the model triage state for a source: the validated result if one was accepted,
 * otherwise why it is blocked, queued for the phone, uncertain or failed. Every attempt records its decision
 * reason, model, policy revision and usage (FR-021).
 */
export async function sourceInference(request: Request, env: Env, now: number, params: string[]) {
  await authenticate(request, env, now);
  const source = await visibleSource(env, params[0]!);
  const db = env.CONTROL_DB;
  const result = await db
    .prepare("SELECT result_json, provenance_json, created_at FROM triage_results WHERE source_id = ? AND source_revision = ? AND json_extract(provenance_json, '$.engine') = 'inference' ORDER BY created_at DESC LIMIT 1")
    .bind(source.id, source.revision)
    .first<{ result_json: string; provenance_json: string; created_at: number }>();
  const job = await db
    .prepare(`SELECT state, output_json, error_code FROM jobs WHERE dedupe_key = ?`)
    .bind(`infer:${INFER_TASK}:${source.id}:${source.revision}`)
    .first<{ state: string; output_json: string | null; error_code: string | null }>();
  const { results } = await db
    .prepare('SELECT * FROM inference_attempts WHERE source_id = ? AND source_revision = ? ORDER BY created_at, rowid')
    .bind(source.id, source.revision)
    .all<AttemptRow>();
  const output = job?.output_json ? (JSON.parse(job.output_json) as { route?: string; reason?: string; state?: string }) : null;
  const latest = results.at(-1);

  let state: string;
  let reason: string | null = null;
  if (result) state = 'succeeded';
  else if (!job) state = 'not_started';
  else if (output?.route === 'blocked') [state, reason] = ['blocked', output.reason ?? null];
  else if (latest) [state, reason] = [latest.state === 'leased' ? 'running_local' : latest.state === 'invalid_output' ? 'failed' : latest.state, latest.reason || latest.error_code];
  else [state, reason] = [job.state === 'complete' ? (output?.state ?? 'complete') : job.state, job.error_code];

  return {
    sourceId: source.id,
    sourceRevision: source.revision,
    task: INFER_TASK,
    state,
    reason,
    result: result ? (JSON.parse(result.result_json) as { output: unknown }).output : null,
    provenance: result ? JSON.parse(result.provenance_json) : null,
    attempts: results.map(attemptView),
  };
}

export const reconcileBody = v.object({ outcome: v.oneOf(['charged'] as const) });

/**
 * POST /inference/attempts/{id}/reconcile — the owner closes an uncertain cloud attempt. Its worst-case
 * reservation is recorded as spent; nothing is dispatched again.
 */
export async function reconcileAttempt(env: Env, now: number, _body: ReturnType<typeof reconcileBody>, id: string) {
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT * FROM inference_attempts WHERE id = ?').bind(id).first<AttemptRow>();
  if (!row) throw notFound();
  if (row.route !== 'cloud' || row.state !== 'uncertain') throw new HttpError(409, 'not_uncertain', 'Only an uncertain cloud attempt can be reconciled.');
  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND state = 'uncertain')", id),
      ...settleStatements(db, JSON.parse(row.reservation_json) as Reservation, null),
      db.prepare("UPDATE inference_attempts SET state = 'reconciled', completed_at = ? WHERE id = ?").bind(now, id),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw new HttpError(409, 'not_uncertain', 'Only an uncertain cloud attempt can be reconciled.');
    throw error;
  }
  return attemptView({ ...row, state: 'reconciled', completed_at: now });
}

// GET /inference/budget — this application's counters for the current periods, with the policy they apply.
export async function inferenceBudget(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const settings = await loadSettings(env.CONTROL_DB);
  const { n } = (await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM inference_attempts WHERE route = 'cloud' AND state = 'uncertain'").first<{ n: number }>()) ?? { n: 0 };
  return {
    policyRevision: settings.revision,
    mode: settings.inference.mode,
    cloudBound: env.AI !== undefined,
    periods: await budgetView(env.CONTROL_DB, now),
    uncertainAttempts: n,
    // Application counters cannot cap spending by other applications that share the account.
    scope: 'application',
  };
}
