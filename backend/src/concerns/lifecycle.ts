import { isAssertionFailure } from '../db/assert';
import { deterministicUuid } from '../ids';
import { CONCERN_COLUMNS, type ConcernRow } from './model';
import { concernSourceIds, writeConcern } from './store';

export const WAKE_BATCH = 50;

/**
 * Scheduled sweep: due snoozes and waiting follow-ups return to open as a new revision (FR-019). A concurrent
 * owner edit wins; the concern is picked up again on the next run if it is still due.
 */
export async function wakeTimedConcerns(db: D1Database, now = Date.now()): Promise<{ woken: number }> {
  const { results } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns
      WHERE (status = 'snoozed' AND snooze_until <= ?) OR (status = 'waiting' AND follow_up_at <= ?)
      ORDER BY CASE WHEN status = 'snoozed' THEN snooze_until ELSE follow_up_at END, id LIMIT ?`)
    .bind(now, now, WAKE_BATCH)
    .all<ConcernRow>();
  let woken = 0;
  for (const row of results) {
    const waiting = row.status === 'waiting';
    const kind = waiting ? 'follow_up_due' : 'snooze_expired';
    // Follow-up is independent of the source deadline and does not mint a new critical/due notification signal.
    const after: ConcernRow = { ...row, status: 'open', snooze_until: null, follow_up_at: waiting ? null : row.follow_up_at, revision: row.revision + 1, updated_at: now };
    try {
      await db.batch(
        writeConcern(db, {
          before: row,
          after,
          kind,
          eventId: await deterministicUuid(`${waiting ? 'follow-up-due' : 'snooze-expired'}:${row.id}:${after.revision}`),
          sourceIds: await concernSourceIds(db, row.id),
          now,
          meaningful: false,
        }),
      );
      woken += 1;
    } catch (error) {
      if (!isAssertionFailure(error)) throw error;
    }
  }
  return { woken };
}
