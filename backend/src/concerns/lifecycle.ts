import { isAssertionFailure } from '../db/assert';
import { deterministicUuid } from '../ids';
import { CONCERN_COLUMNS, type ConcernRow } from './model';
import { concernSourceIds, writeConcern } from './store';

export const SNOOZE_BATCH = 50;

/**
 * Scheduled sweep: snoozes whose time has come return to open as a new revision (lld.md#concerns). A concurrent
 * owner edit wins; the concern is picked up again on the next run if it is still due.
 */
export async function wakeSnoozed(db: D1Database, now = Date.now()): Promise<{ woken: number }> {
  const { results } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE status = 'snoozed' AND snooze_until <= ? ORDER BY snooze_until, id LIMIT ?`)
    .bind(now, SNOOZE_BATCH)
    .all<ConcernRow>();
  let woken = 0;
  for (const row of results) {
    const after: ConcernRow = { ...row, status: 'open', snooze_until: null, revision: row.revision + 1, updated_at: now };
    try {
      await db.batch(
        writeConcern(db, {
          before: row,
          after,
          kind: 'snooze_expired',
          eventId: await deterministicUuid(`snooze-expired:${row.id}:${after.revision}`),
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
