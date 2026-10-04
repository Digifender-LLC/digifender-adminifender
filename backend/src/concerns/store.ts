import { assertion } from '../db/assert';
import { notFound } from '../http/errors';
import { concernEventStatements } from '../notifications/events';
import { CONCERN_COLUMNS, type ConcernRow, concernView, snapshot } from './model';

export interface Guard {
  sql: string;
  params: unknown[];
}

const NO_GUARD: Guard = { sql: '1', params: [] };

export async function loadConcern(db: D1Database, id: string): Promise<{ row: ConcernRow; sourceIds: string[] }> {
  const row = await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id = ?`).bind(id).first<ConcernRow>();
  if (!row) throw notFound();
  return { row, sourceIds: await concernSourceIds(db, id) };
}

export async function concernSourceIds(db: D1Database, id: string): Promise<string[]> {
  const { results } = await db
    .prepare("SELECT cs.source_id FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id WHERE cs.concern_id = ? AND r.state NOT IN ('deleting', 'deleted') ORDER BY r.received_at, r.id")
    .bind(id)
    .all<{ source_id: string }>();
  return results.map((r) => r.source_id);
}

/**
 * One concern revision as batch statements: precondition assertion, row write, event, full change projection,
 * for meaningful review edits the review timestamp (lld.md#concerns), and any notification it implies. `before` null creates the concern.
 * `after.revision` must be `before.revision + 1` (or 1 when creating).
 */
export function writeConcern(
  db: D1Database,
  input: {
    before: ConcernRow | null;
    after: ConcernRow;
    kind: string;
    eventId: string;
    sourceIds: readonly string[];
    now: number;
    meaningful: boolean;
    eventDetail?: Record<string, unknown>;
    /** Evidence links moved away from this concern by a merge or split. */
    removedSourceIds?: readonly string[];
    guard?: Guard;
  },
): D1PreparedStatement[] {
  const { before, after, now } = input;
  const guard = input.guard ?? NO_GUARD;
  const columns = CONCERN_COLUMNS.filter((c) => c !== 'id');
  const values = columns.map((c) => after[c]);
  const statements: D1PreparedStatement[] = [];
  if (before) {
    statements.push(assertion(db, 'EXISTS (SELECT 1 FROM concerns WHERE id = ? AND revision = ?)', before.id, before.revision));
    statements.push(
      db.prepare(`UPDATE concerns SET ${columns.map((c) => `${c} = ?`).join(', ')} WHERE id = ? AND revision = ? AND ${guard.sql}`)
        .bind(...values, after.id, before.revision, ...guard.params),
    );
  } else {
    statements.push(assertion(db, 'NOT EXISTS (SELECT 1 FROM concerns WHERE id = ?)', after.id));
    statements.push(
      db.prepare(`INSERT INTO concerns(id, ${columns.join(', ')}) SELECT ?, ${columns.map(() => '?').join(', ')} WHERE ${guard.sql}`)
        .bind(after.id, ...values, ...guard.params),
    );
  }
  // Statements after the write apply only when this exact revision landed.
  const landed = `EXISTS (SELECT 1 FROM concerns WHERE id = ? AND revision = ?) AND ${guard.sql}`;
  const landedParams = [after.id, after.revision, ...guard.params];
  for (const sourceId of input.sourceIds) {
    statements.push(db.prepare(`INSERT OR IGNORE INTO concern_sources(concern_id, source_id) SELECT ?, ? WHERE ${landed}`).bind(after.id, sourceId, ...landedParams));
  }
  const removed = input.removedSourceIds ?? [];
  if (removed.length > 0) {
    statements.push(
      db.prepare(`DELETE FROM concern_sources WHERE concern_id = ? AND source_id IN (${removed.map(() => '?').join(',')}) AND ${landed}`).bind(after.id, ...removed, ...landedParams),
    );
  }
  statements.push(
    db.prepare(`INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) SELECT ?, ?, ?, ?, ?, ?, ?, ? WHERE ${landed}`)
      .bind(
        input.eventId,
        after.id,
        before?.revision ?? 0,
        after.revision,
        input.kind,
        JSON.stringify(before ? snapshot(before) : null),
        JSON.stringify({ ...snapshot(after), ...(input.eventDetail ? { detail: input.eventDetail } : {}) }),
        now,
        ...landedParams,
      ),
  );
  statements.push(changeStatement(db, after, input.sourceIds, now, landed, landedParams));
  if (input.meaningful) statements.push(meaningfulReview(db, now, landed, landedParams));
  statements.push(...concernEventStatements(db, before, after, now, landed, landedParams));
  return statements;
}

export function changeStatement(db: D1Database, row: ConcernRow, sourceIds: readonly string[], now: number, condition = '1', params: unknown[] = []): D1PreparedStatement {
  return db
    .prepare(`INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) SELECT 'concern', ?, ?, 0, ?, ? WHERE ${condition}`)
    .bind(row.id, row.revision, JSON.stringify(concernView(row, sourceIds)), now, ...params);
}

/** Meaningful review time; app launch, sync, push receipt and mark_read never call this (FR-030). */
export function meaningfulReview(db: D1Database, now: number, condition = '1', params: unknown[] = []): D1PreparedStatement {
  return db
    .prepare(`INSERT INTO review_state(id, last_meaningful_at, last_reviewed_seq, review_epoch) SELECT 'owner', ?, 0, 'v1' WHERE ${condition} ON CONFLICT(id) DO UPDATE SET last_meaningful_at = excluded.last_meaningful_at`)
    .bind(now, ...params);
}
