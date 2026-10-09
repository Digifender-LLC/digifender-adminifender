import type { Env } from '../env';
import { settleExpiredAtCap } from './runner';

/** Bounded per-minute batch (tdd.md#backend-and-contracts). */
export const RECONCILE_BATCH = 50;

/**
 * Scheduled reconciler: ends jobs whose lease expired with the attempt cap spent (they are never published again),
 * publishes pending outbox rows and re-publishes due or lease-expired jobs.
 * Duplicate delivery is safe because claims are fenced in D1.
 */
export async function reconcile(env: Pick<Env, 'CONTROL_DB' | 'WORK_QUEUE'>, now = Date.now()): Promise<{ published: number }> {
  const db = env.CONTROL_DB;
  await settleExpiredAtCap(db, now, undefined, RECONCILE_BATCH);
  const { results: outbox } = await db
    .prepare("SELECT id, reference_id FROM outbox WHERE kind = 'job' AND state = 'pending' AND next_at <= ? ORDER BY next_at LIMIT ?")
    .bind(now, RECONCILE_BATCH)
    .all<{ id: string; reference_id: string }>();
  const { results: due } = await db
    .prepare(
      "SELECT id FROM jobs WHERE (state = 'pending' AND next_at <= ?) OR (state IN ('leased', 'running') AND lease_until < ?) ORDER BY next_at LIMIT ?",
    )
    .bind(now, now, RECONCILE_BATCH)
    .all<{ id: string }>();

  const jobIds = [...new Set([...outbox.map((row) => row.reference_id), ...due.map((row) => row.id)])];
  if (jobIds.length === 0) return { published: 0 };
  await env.WORK_QUEUE.sendBatch(jobIds.map((jobId) => ({ body: { version: 1 as const, jobId } })));
  if (outbox.length > 0) {
    await db.batch(outbox.map((row) => db.prepare("UPDATE outbox SET state = 'sent', attempts = attempts + 1 WHERE id = ? AND state = 'pending'").bind(row.id)));
  }
  return { published: jobIds.length };
}
