import { authenticate } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import { CONCERN_COLUMNS, type ConcernRow, concernView } from '../concerns/model';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';

/** Deterministic briefing cards are bounded per section; the full lists stay in GET /concerns. */
export const SECTION_LIMIT = 100;
/** Bounded content-free snapshot receipts; an older open screen can refresh if its receipt expires. */
export const SNAPSHOT_LIMIT = 200;
export const SNAPSHOT_TTL_MS = 24 * 60 * 60_000;
/** Events that make a concern new or changed for the owner; their own review edits and non-material links do not. */
const ATTENTION_EVENTS = ['created', 'evidence_added', 'model_triaged', 'snooze_expired', 'follow_up_due', 'split_from'];

interface ReviewState {
  last_meaningful_at: number | null;
  last_reviewed_seq: number;
}

async function reviewState(db: D1Database): Promise<ReviewState> {
  return (await db.prepare('SELECT last_meaningful_at, last_reviewed_seq FROM review_state WHERE id = ?').bind('owner').first<ReviewState>()) ?? { last_meaningful_at: null, last_reviewed_seq: 0 };
}

async function concernsByIds(db: D1Database, ids: string[]): Promise<ConcernRow[]> {
  const rows: ConcernRow[] = [];
  for (let start = 0; start < ids.length; start += 100) {
    const page = ids.slice(start, start + 100);
    const { results } = await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id IN (${page.map(() => '?').join(',')})`).bind(...page).all<ConcernRow>();
    rows.push(...results);
  }
  return rows;
}

/**
 * GET /briefings/current (lld.md#briefings): since the persisted review cursor, new and changed concerns, plus
 * overdue and unacknowledged unresolved items. The snapshot is stored so Mark reviewed covers only what was shown.
 */
export async function currentBriefing(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const state = await reviewState(db);
  const observedSeq = (await db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
  const marks = ATTENTION_EVENTS.map(() => '?').join(',');
  const { results: touched } = await db
    .prepare(
      `SELECT c.entity_id AS id, MAX(CASE WHEN e.kind IN ('created', 'split_from') THEN 1 ELSE 0 END) AS is_new, MIN(c.seq) AS seq
       FROM changes c JOIN concern_events e ON e.concern_id = c.entity_id AND e.to_revision = c.revision
       JOIN concerns live ON live.id = c.entity_id
       WHERE c.entity_type = 'concern' AND c.seq > ? AND c.seq <= ? AND e.kind IN (${marks})
       GROUP BY c.entity_id ORDER BY seq, c.entity_id LIMIT ?`,
    )
    .bind(state.last_reviewed_seq, observedSeq, ...ATTENTION_EVENTS, SECTION_LIMIT * 2 + 1)
    .all<{ id: string; is_new: number; seq: number }>();
  const { results: overdue } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE status <> 'resolved' AND due_at IS NOT NULL AND due_at < ? ORDER BY due_at, id LIMIT ?`)
    .bind(now, SECTION_LIMIT)
    .all<ConcernRow>();
  const { results: unacknowledged } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE status IN ('open', 'waiting') AND acknowledged_at IS NULL ORDER BY updated_at DESC, id DESC LIMIT ?`)
    .bind(SECTION_LIMIT)
    .all<ConcernRow>();
  const { results: newSources } = await db
    .prepare("SELECT id, ready_seq AS seq FROM source_registry WHERE ready_seq > ? AND ready_seq <= ? AND state = 'ready' AND admission = 'normal' ORDER BY ready_seq, id LIMIT ?")
    .bind(state.last_reviewed_seq, observedSeq, SECTION_LIMIT + 1)
    .all<{ id: string; seq: number }>();

  // Review oldest pending work first and stop before the first item that would be omitted by ANY section.
  // Current cards may already contain a later revision; conservatively showing them again is safe, skipping
  // an unseen concern or source is not. Missing/deleted concerns were excluded by the live join above.
  const firstOmitted = [
    touched.filter((t) => t.is_new === 1)[SECTION_LIMIT]?.seq,
    touched.filter((t) => t.is_new !== 1)[SECTION_LIMIT]?.seq,
    newSources[SECTION_LIMIT]?.seq,
  ].filter((seq): seq is number => seq !== undefined);
  const snapshotSeq = firstOmitted.length === 0 ? observedSeq : Math.min(observedSeq, ...firstOmitted.map((seq) => seq - 1));
  const selected = touched.filter((t) => t.seq <= snapshotSeq);
  const touchedRows = new Map((await concernsByIds(db, selected.map((t) => t.id))).map((r) => [r.id, r]));
  const shown = [...touchedRows.values(), ...overdue, ...unacknowledged];
  const included: Record<string, number> = {};
  for (const row of shown) included[row.id] = row.revision;
  const membership = await sourceIdsFor(db, Object.keys(included));
  const view = (row: ConcernRow) => concernView(row, membership.get(row.id) ?? []);
  const briefing = { id: crypto.randomUUID(), through_time: now };
  // A fresh identity fixes the displayed membership too: time alone can move a card into the overdue section
  // without changing its revision. Never reuse one review identity for different displayed sections.
  try {
    await db.batch([
      assertion(db, `(SELECT COALESCE(MAX(seq), 0) FROM changes) = ?
        AND COALESCE((SELECT last_reviewed_seq FROM review_state WHERE id = 'owner'), 0) = ?
        AND COALESCE((SELECT last_meaningful_at FROM review_state WHERE id = 'owner'), 0) = ?`,
      observedSeq, state.last_reviewed_seq, state.last_meaningful_at ?? 0),
      db.prepare('INSERT INTO briefings(id, snapshot_seq, through_time, included_revisions_json, summary_json, created_at) VALUES (?, ?, ?, ?, NULL, ?)')
        .bind(briefing.id, snapshotSeq, now, JSON.stringify(included), now),
      db.prepare('DELETE FROM briefings WHERE created_at < ?').bind(now - SNAPSHOT_TTL_MS),
      db.prepare('DELETE FROM briefings WHERE id <> ? AND id NOT IN (SELECT id FROM briefings ORDER BY created_at DESC, id DESC LIMIT ?)')
        .bind(briefing.id, SNAPSHOT_LIMIT - 1),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw new HttpError(409, 'snapshot_changed', 'The briefing changed while loading. Refresh it.');
    throw error;
  }
  const pick = (isNew: boolean) => selected.filter((t) => (t.is_new === 1) === isNew).flatMap((t) => (touchedRows.has(t.id) ? [view(touchedRows.get(t.id)!)] : []));
  return {
    id: briefing.id,
    snapshotSeq,
    throughTime: new Date(briefing.through_time).toISOString(),
    lastReviewedAt: state.last_meaningful_at === null ? null : new Date(state.last_meaningful_at).toISOString(),
    newConcerns: pick(true),
    changedConcerns: pick(false),
    overdue: overdue.map(view),
    unacknowledged: unacknowledged.map(view),
    newSourceIds: newSources.filter((s) => s.seq <= snapshotSeq).map((s) => s.id),
    hasMore: snapshotSeq < observedSeq,
    // Optional inference summaries arrive with the inference slice; deterministic cards are always present.
    summary: null,
  };
}

async function sourceIdsFor(db: D1Database, ids: string[]): Promise<Map<string, string[]>> {
  const out = new Map<string, string[]>(ids.map((id) => [id, []]));
  for (let start = 0; start < ids.length; start += 100) {
    const page = ids.slice(start, start + 100);
    const { results } = await db
      .prepare(
        `SELECT cs.concern_id, cs.source_id FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id
         WHERE cs.concern_id IN (${page.map(() => '?').join(',')}) AND r.state NOT IN ('deleting', 'deleted') ORDER BY r.received_at, r.id`,
      )
      .bind(...page)
      .all<{ concern_id: string; source_id: string }>();
    for (const r of results) out.get(r.concern_id)?.push(r.source_id);
  }
  return out;
}

export const reviewBody = v.object({});

/**
 * POST /briefings/{id}/review — advances the review cursor through that snapshot only (never backwards) and the
 * meaningful-review time. It acknowledges and resolves nothing.
 */
export async function reviewBriefing(env: Env, now: number, _body: ReturnType<typeof reviewBody>, id: string) {
  const db = env.CONTROL_DB;
  const briefing = await db.prepare('SELECT id, snapshot_seq, reviewed_at FROM briefings WHERE id = ? AND created_at >= ?').bind(id, now - SNAPSHOT_TTL_MS).first<{ id: string; snapshot_seq: number; reviewed_at: number | null }>();
  if (!briefing) throw notFound();
  if (briefing.reviewed_at !== null) throw new HttpError(409, 'already_reviewed', 'This briefing was already marked reviewed.');
  try {
    await db.batch([
      assertion(db, 'EXISTS (SELECT 1 FROM briefings WHERE id = ? AND reviewed_at IS NULL AND created_at >= ?)', id, now - SNAPSHOT_TTL_MS),
      db.prepare('UPDATE briefings SET reviewed_at = ? WHERE id = ? AND reviewed_at IS NULL').bind(now, id),
      db.prepare(
        `INSERT INTO review_state(id, last_meaningful_at, last_reviewed_seq, review_epoch) VALUES ('owner', ?, ?, 'v1')
         ON CONFLICT(id) DO UPDATE SET last_meaningful_at = MAX(COALESCE(review_state.last_meaningful_at, 0), excluded.last_meaningful_at), last_reviewed_seq = MAX(review_state.last_reviewed_seq, excluded.last_reviewed_seq)`,
      ).bind(now, briefing.snapshot_seq),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw new HttpError(409, 'snapshot_changed', 'The briefing changed while recording the review. Refresh it.');
    throw error;
  }
  return { id, reviewedThroughSeq: briefing.snapshot_seq, reviewedAt: new Date(now).toISOString() };
}
