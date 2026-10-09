import { isAssertionFailure } from '../db/assert';
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
/**
 * SQL guard for statements a handler runs *while* its claim is still being held (before it returns a result): true
 * only while this exact claim is the running one. A newer claim replaces the token, so a stale attempt changes nothing.
 */
export const LEASE_GUARD = "EXISTS (SELECT 1 FROM jobs WHERE id = ? AND lease_token = ? AND state = 'running')";
export const guardParams = (job: ClaimedJob): [string, string] => [job.id, job.leaseToken];

export type JobResult =
  | { kind: 'complete'; output: unknown; statements?: D1PreparedStatement[] }
  | { kind: 'retry'; errorCode: string }
  /** Not yet: wait until `until` without spending a retry (for example quiet hours). */
  | { kind: 'defer'; until: number }
  /** Terminal failure; dependent statements (for example a visible failed state) commit with it. */
  | { kind: 'fail'; errorCode: string; statements?: D1PreparedStatement[] };

export type JobHandler = (job: ClaimedJob, env: Env, now: number) => Promise<JobResult>;

/** Error code of a job whose last permitted attempt ended without a result (its lease ran out): content-free. */
export const LEASE_EXPIRED = 'lease_expired';

/**
 * The attempt cap also holds for attempts that never settled. An attempt whose invocation was killed (or whose D1
 * settle never arrived) leaves the job `running` until its lease ends; a retry costs an attempt only when it settles, so
 * without this a job would be claimed again after every lease, forever. A `leased` or `running` job whose lease has
 * expired with the cap already spent becomes `dead` here, with the lease cleared, and can never be claimed again. One
 * statement selects by state, expiry and attempt count, so it cannot touch a live lease, a settled job or one still
 * below the cap, and a stale settle of the old claim finds neither its token nor the `running` state. Pass a job ID to
 * resolve one job, or none to resolve up to `limit` of them (the scheduled reconciler). Returns how many became dead.
 */
export async function settleExpiredAtCap(db: D1Database, now: number, jobId?: string, limit = 50): Promise<number> {
  const result = await db
    .prepare(
      `UPDATE jobs SET state = 'dead', error_code = ?, lease_token = NULL, lease_until = NULL
       WHERE id IN (SELECT id FROM jobs WHERE state IN ('leased', 'running') AND lease_until < ? AND attempt >= ? AND (? IS NULL OR id = ?) LIMIT ?)`,
    )
    .bind(LEASE_EXPIRED, now, MAX_ATTEMPTS, jobId ?? null, jobId ?? null, limit)
    .run();
  return result.meta.changes;
}

/**
 * Claims a due job by conditional update with a fresh fencing token (lld.md#jobs). Only replay-safe kinds are
 * run here; an expired lease on them is retried, but only while attempts remain (`settleExpiredAtCap` ends the rest), so
 * no claim ever carries an attempt number above `MAX_ATTEMPTS`. Returns null when another worker holds or finished it.
 */
export async function claimJob(db: D1Database, jobId: string, now: number): Promise<ClaimedJob | null> {
  const token = crypto.randomUUID();
  const row = await db
    .prepare(
      `UPDATE jobs SET state = 'running', attempt = attempt + 1, lease_token = ?, lease_until = ?
       WHERE id = ? AND ((state = 'pending' AND next_at <= ?) OR (state IN ('leased', 'running') AND lease_until < ? AND attempt < ?))
       RETURNING id, kind, source_id, input_revision, attempt, input_json`,
    )
    .bind(token, now + LEASE_MS, jobId, now, now, MAX_ATTEMPTS)
    .first<{ id: string; kind: string; source_id: string | null; input_revision: number | null; attempt: number; input_json: string }>();
  if (!row) return null;
  return { id: row.id, kind: row.kind, sourceId: row.source_id, inputRevision: row.input_revision, attempt: row.attempt, leaseToken: token, input: JSON.parse(row.input_json) };
}

/** Settles a claim. Dependent statements must include CLAIM_GUARD so a stale claim changes nothing. */
async function settle(db: D1Database, job: ClaimedJob, result: JobResult, now: number): Promise<{ applied: boolean; result: JobResult }> {
  const fence = 'WHERE id = ? AND lease_token = ? AND state = ?';
  let head: D1PreparedStatement;
  let dependents: D1PreparedStatement[] = [];
  if (result.kind === 'complete') {
    head = db.prepare(`UPDATE jobs SET state = 'complete', output_json = ?, error_code = NULL, lease_until = NULL ${fence}`).bind(JSON.stringify(result.output ?? null), job.id, job.leaseToken, 'running');
    dependents = result.statements ?? [];
  } else if (result.kind === 'defer') {
    head = db.prepare(`UPDATE jobs SET state = 'pending', next_at = ?, attempt = attempt - 1, error_code = NULL, lease_token = NULL, lease_until = NULL ${fence}`).bind(result.until, job.id, job.leaseToken, 'running');
  } else if (result.kind === 'retry' && job.attempt < MAX_ATTEMPTS) {
    head = db.prepare(`UPDATE jobs SET state = 'pending', next_at = ?, error_code = ?, lease_token = NULL, lease_until = NULL ${fence}`).bind(now + retryDelay(job.attempt), result.errorCode, job.id, job.leaseToken, 'running');
  } else {
    head = db.prepare(`UPDATE jobs SET state = ?, error_code = ?, lease_until = NULL ${fence}`).bind(result.kind === 'retry' ? 'dead' : 'failed', result.errorCode, job.id, job.leaseToken, 'running');
    if (result.kind === 'fail') dependents = result.statements ?? [];
  }
  let results: D1Result[];
  try {
    results = await db.batch([head, ...dependents]);
  } catch (error) {
    // A failed batch assertion (for example a concern revision that moved under the job) rolls the batch back;
    // the job retries against current state.
    if (result.kind !== 'complete' || !isAssertionFailure(error)) throw error;
    return settle(db, job, { kind: 'retry', errorCode: 'commit_conflict' }, now);
  }
  // A zero-row fenced update is not an error in D1; check its effect explicitly.
  return { applied: (results[0]?.meta.changes ?? 0) === 1, result };
}

export interface RunOutcome {
  jobId: string;
  status: 'skipped' | 'complete' | 'deferred' | 'retry' | 'failed' | 'dead' | 'stale';
}

export async function runJob(env: Env, handlers: Readonly<Record<string, JobHandler>>, jobId: string, now = Date.now()): Promise<RunOutcome> {
  // A lease that expired with the attempt cap spent ends the job instead of starting an attempt past the cap.
  if ((await settleExpiredAtCap(env.CONTROL_DB, now, jobId)) > 0) return { jobId, status: 'dead' };
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
  const settled = await settle(env.CONTROL_DB, job, result, now);
  if (!settled.applied) return { jobId, status: 'stale' };
  result = settled.result;
  if (result.kind === 'complete') return { jobId, status: 'complete' };
  if (result.kind === 'fail') return { jobId, status: 'failed' };
  if (result.kind === 'defer') return { jobId, status: 'deferred' };
  return { jobId, status: job.attempt < MAX_ATTEMPTS ? 'retry' : 'dead' };
}
