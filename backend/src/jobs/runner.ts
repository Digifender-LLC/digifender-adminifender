import type { Env } from '../env';

/** Baseline retry policy (baseline.md defaults): 5 attempts, 30 s doubling, capped at 1 hour; 5-minute lease. */
export const MAX_ATTEMPTS = 5;
export const LEASE_MS = 5 * 60 * 1000;
const BASE_DELAY_MS = 30 * 1000;
const MAX_DELAY_MS = 60 * 60 * 1000;

export function retryDelay(attempt: number): number {
  return Math.min(BASE_DELAY_MS * 2 ** Math.max(0, attempt - 1), MAX_DELAY_MS);
}

export interface ClaimedJob {
  id: string;
  kind: string;
  sourceId: string | null;
  inputRevision: number | null;
  attempt: number;
  leaseToken: string;
  input: unknown;
}

/** SQL guard for statements that may only apply while this claim still holds. Bind with guardParams(job). */
export const CLAIM_GUARD = "EXISTS (SELECT 1 FROM jobs WHERE id = ? AND lease_token = ? AND state IN ('complete', 'failed'))";
export const guardParams = (job: ClaimedJob): [string, string] => [job.id, job.leaseToken];

export type JobResult =
  | { kind: 'complete'; output: unknown; statements?: D1PreparedStatement[] }
  | { kind: 'retry'; errorCode: string }
  /** Terminal failure; dependent statements (for example a visible failed state) commit with it. */
  | { kind: 'fail'; errorCode: string; statements?: D1PreparedStatement[] };

export type JobHandler = (job: ClaimedJob, env: Env, now: number) => Promise<JobResult>;

/**
 * Claims a due job by conditional update with a fresh fencing token (lld.md#jobs). Only replay-safe kinds are
 * run here; an expired lease on them is retried. Returns null when another worker holds or finished it.
 */
export async function claimJob(db: D1Database, jobId: string, now: number): Promise<ClaimedJob | null> {
  const token = crypto.randomUUID();
  const row = await db
    .prepare(
      `UPDATE jobs SET state = 'running', attempt = attempt + 1, lease_token = ?, lease_until = ?
       WHERE id = ? AND ((state = 'pending' AND next_at <= ?) OR (state IN ('leased', 'running') AND lease_until < ?))
       RETURNING id, kind, source_id, input_revision, attempt, input_json`,
    )
    .bind(token, now + LEASE_MS, jobId, now, now)
    .first<{ id: string; kind: string; source_id: string | null; input_revision: number | null; attempt: number; input_json: string }>();
  if (!row) return null;
  return { id: row.id, kind: row.kind, sourceId: row.source_id, inputRevision: row.input_revision, attempt: row.attempt, leaseToken: token, input: JSON.parse(row.input_json) };
}

/** Settles a claim. Dependent statements must include CLAIM_GUARD so a stale claim changes nothing. */
async function settle(db: D1Database, job: ClaimedJob, result: JobResult, now: number): Promise<boolean> {
  const fence = 'WHERE id = ? AND lease_token = ? AND state = ?';
  let head: D1PreparedStatement;
  let dependents: D1PreparedStatement[] = [];
  if (result.kind === 'complete') {
    head = db.prepare(`UPDATE jobs SET state = 'complete', output_json = ?, error_code = NULL, lease_until = NULL ${fence}`).bind(JSON.stringify(result.output ?? null), job.id, job.leaseToken, 'running');
    dependents = result.statements ?? [];
  } else if (result.kind === 'retry' && job.attempt < MAX_ATTEMPTS) {
    head = db.prepare(`UPDATE jobs SET state = 'pending', next_at = ?, error_code = ?, lease_token = NULL, lease_until = NULL ${fence}`).bind(now + retryDelay(job.attempt), result.errorCode, job.id, job.leaseToken, 'running');
  } else {
    head = db.prepare(`UPDATE jobs SET state = ?, error_code = ?, lease_until = NULL ${fence}`).bind(result.kind === 'retry' ? 'dead' : 'failed', result.errorCode, job.id, job.leaseToken, 'running');
    if (result.kind === 'fail') dependents = result.statements ?? [];
  }
  const results = await db.batch([head, ...dependents]);
  // A zero-row fenced update is not an error in D1; check its effect explicitly.
  return (results[0]?.meta.changes ?? 0) === 1;
}

export interface RunOutcome {
  jobId: string;
  status: 'skipped' | 'complete' | 'retry' | 'failed' | 'dead' | 'stale';
}

export async function runJob(env: Env, handlers: Readonly<Record<string, JobHandler>>, jobId: string, now = Date.now()): Promise<RunOutcome> {
  const job = await claimJob(env.CONTROL_DB, jobId, now);
  if (!job) return { jobId, status: 'skipped' };
  const handler = handlers[job.kind];
  let result: JobResult;
  if (!handler) {
    result = { kind: 'fail', errorCode: 'unknown_job_kind' };
  } else {
    try {
      result = await handler(job, env, now);
    } catch {
      // Error text may contain content; only the class is kept.
      result = { kind: 'retry', errorCode: 'handler_exception' };
    }
  }
  const applied = await settle(env.CONTROL_DB, job, result, now);
  if (!applied) return { jobId, status: 'stale' };
  if (result.kind === 'complete') return { jobId, status: 'complete' };
  if (result.kind === 'fail') return { jobId, status: 'failed' };
  return { jobId, status: job.attempt < MAX_ATTEMPTS ? 'retry' : 'dead' };
}
