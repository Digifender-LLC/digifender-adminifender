import { authenticate } from '../auth/sessions';
import { CONCERN_COLUMNS, type ConcernRow, concernView } from '../concerns/model';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';

/** Deterministic briefing cards are bounded per section; the full lists stay in GET /concerns. */
export const SECTION_LIMIT = 100;
/** Events that make a concern new or changed for the owner; their own review edits and non-material links do not. */
const ATTENTION_EVENTS = ['created', 'evidence_added', 'snooze_expired', 'split_from'];

interface ReviewState {
  last_meaningful_at: number | null;
  last_reviewed_seq: number;
}

async function reviewState(db: D1Database): Promise<ReviewState> {
  return (await db.prepare('SELECT last_meaningful_at, last_reviewed_seq FROM review_state WHERE id = ?').bind('owner').first<ReviewState>()) ?? { last_meaningful_at: null, last_reviewed_seq: 0 };
}

/** D1 allows 100 bound parameters per query (developers.cloudflare.com/d1/platform/limits, checked 2026-10-08); a briefing can name 400 concerns. */
const ID_BATCH = 90;
function inBatches(ids: string[]): string[][] {
  const out: string[][] = [];
  for (let i = 0; i < ids.length; i += ID_BATCH) out.push(ids.slice(i, i + ID_BATCH));
  return out;
}

async function concernsByIds(db: D1Database, ids: string[]): Promise<ConcernRow[]> {
  const rows: ConcernRow[] = [];
  for (const batch of inBatches(ids)) {
    const { results } = await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id IN (${batch.map(() => '?').join(',')})`).bind(...batch).all<ConcernRow>();
    rows.push(...results);
  }
  return rows;
}

interface Attention {
  id: string;
  /** Earliest and latest attention change after the review cursor; the earliest orders the cap, the latest the cards. */
  first_seq: number;
  seq: number;
}

/**
 * Concerns with attention events in (after, upTo], oldest first and at most one past the section cap. A concern is
 * new when its creation (or split) falls in the range, which then holds its earliest event; otherwise it changed.
 */
async function attentionConcerns(db: D1Database, after: number, upTo: number, isNew: boolean): Promise<Attention[]> {
  const marks = ATTENTION_EVENTS.map(() => '?').join(',');
  const { results } = await db
    .prepare(
      `SELECT c.entity_id AS id, MIN(c.seq) AS first_seq, MAX(c.seq) AS seq
       FROM changes c JOIN concern_events e ON e.concern_id = c.entity_id AND e.to_revision = c.revision
       WHERE c.entity_type = 'concern' AND c.seq > ? AND c.seq <= ? AND e.kind IN (${marks})
       GROUP BY c.entity_id HAVING MAX(CASE WHEN e.kind IN ('created', 'split_from') THEN 1 ELSE 0 END) = ?
       ORDER BY first_seq LIMIT ?`,
    )
    .bind(after, upTo, ...ATTENTION_EVENTS, isNew ? 1 : 0, SECTION_LIMIT + 1)
    .all<Attention>();
  return results;
}

/**
 * GET /briefings/current (lld.md#briefings): since the persisted review cursor, new and changed concerns, plus
 * overdue and unacknowledged unresolved items. The snapshot is stored so Mark reviewed covers only what was shown.
 * New and changed concerns and new sources are capped per section; when work exceeds a cap, `snapshotSeq` stops before
 * the first omitted item and `hasMore` is true, so repeated reviewed briefings reach every item in order.
 */
export async function currentBriefing(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const state = await reviewState(db);
  const latestSeq = (await db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
  // Each list is read oldest first with one row past its cap. If a cap overflows, the snapshot ends just before the
  // first omitted item, so Mark reviewed can never move the cursor over work that was not shown.
  const newConcerns = await attentionConcerns(db, state.last_reviewed_seq, latestSeq, true);
  const changedConcerns = await attentionConcerns(db, state.last_reviewed_seq, latestSeq, false);
  const { results: readySources } = await db
    .prepare("SELECT id, ready_seq AS seq, ready_seq AS first_seq FROM source_registry WHERE ready_seq > ? AND ready_seq <= ? AND state = 'ready' AND admission = 'normal' ORDER BY ready_seq LIMIT ?")
    .bind(state.last_reviewed_seq, latestSeq, SECTION_LIMIT + 1)
    .all<{ id: string; seq: number; first_seq: number }>();
  let snapshotSeq = latestSeq;
  for (const list of [newConcerns, changedConcerns, readySources]) {
    if (list.length > SECTION_LIMIT) snapshotSeq = Math.min(snapshotSeq, list[SECTION_LIMIT]!.first_seq - 1);
  }
  const covered = <T extends { first_seq: number }>(list: T[]) => list.filter((t) => t.first_seq <= snapshotSeq).slice(0, SECTION_LIMIT);
  const byRecent = <T extends { seq: number }>(list: T[]) => [...list].sort((a, b) => b.seq - a.seq);
  const touched = [
    ...byRecent(covered(newConcerns)).map((t) => ({ ...t, is_new: true })),
    ...byRecent(covered(changedConcerns)).map((t) => ({ ...t, is_new: false })),
  ];
  const newSources = byRecent(covered(readySources));
  const touchedRows = new Map((await concernsByIds(db, touched.map((t) => t.id))).map((r) => [r.id, r]));
  const { results: overdue } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE status <> 'resolved' AND due_at IS NOT NULL AND due_at < ? ORDER BY due_at, id LIMIT ?`)
    .bind(now, SECTION_LIMIT)
    .all<ConcernRow>();
  const { results: unacknowledged } = await db
    .prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE status IN ('open', 'waiting') AND acknowledged_at IS NULL ORDER BY updated_at DESC, id DESC LIMIT ?`)
    .bind(SECTION_LIMIT)
    .all<ConcernRow>();

  const shown = [...touchedRows.values(), ...overdue, ...unacknowledged];
  const included: Record<string, number> = {};
  for (const row of shown) included[row.id] = row.revision;
  const membership = await sourceIdsFor(db, Object.keys(included));
  const view = (row: ConcernRow) => concernView(row, membership.get(row.id) ?? []);

  // Reuse an unreviewed snapshot at the same cursor so repeated opens do not multiply rows.
  const includedJson = JSON.stringify(included);
  let briefing = await db
    .prepare('SELECT id, through_time FROM briefings WHERE snapshot_seq = ? AND reviewed_at IS NULL AND included_revisions_json = ? ORDER BY created_at DESC LIMIT 1')
    .bind(snapshotSeq, includedJson)
    .first<{ id: string; through_time: number }>();
  if (!briefing) {
    // A complete snapshot is current as of now; a bounded one is only current as of its last covered change.
    const lastCovered = snapshotSeq < latestSeq ? await db.prepare('SELECT created_at FROM changes WHERE seq <= ? ORDER BY seq DESC LIMIT 1').bind(snapshotSeq).first<{ created_at: number }>() : null;
    briefing = { id: crypto.randomUUID(), through_time: Math.min(now, lastCovered?.created_at ?? now) };
    await db.prepare('INSERT INTO briefings(id, snapshot_seq, through_time, included_revisions_json, summary_json, created_at) VALUES (?, ?, ?, ?, NULL, ?)')
      .bind(briefing.id, snapshotSeq, briefing.through_time, includedJson, now)
      .run();
  }
  const pick = (isNew: boolean) => touched.filter((t) => t.is_new === isNew).flatMap((t) => (touchedRows.has(t.id) ? [view(touchedRows.get(t.id)!)] : []));
  return {
    id: briefing.id,
    snapshotSeq,
    throughTime: new Date(briefing.through_time).toISOString(),
    lastReviewedAt: state.last_meaningful_at === null ? null : new Date(state.last_meaningful_at).toISOString(),
    newConcerns: pick(true),
    changedConcerns: pick(false),
    overdue: overdue.map(view),
    unacknowledged: unacknowledged.map(view),
    newSourceIds: newSources.map((s) => s.id),
    hasMore: snapshotSeq < latestSeq,
    // Optional inference summaries arrive with the inference slice; deterministic cards are always present.
    summary: null,
  };
}

async function sourceIdsFor(db: D1Database, ids: string[]): Promise<Map<string, string[]>> {
  const out = new Map<string, string[]>(ids.map((id) => [id, []]));
  for (const batch of inBatches(ids)) {
    const { results } = await db
      .prepare(
        `SELECT cs.concern_id, cs.source_id FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id
         WHERE cs.concern_id IN (${batch.map(() => '?').join(',')}) AND r.state NOT IN ('deleting', 'deleted') ORDER BY r.received_at, r.id`,
      )
      .bind(...batch)
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
  const briefing = await db.prepare('SELECT id, snapshot_seq, reviewed_at FROM briefings WHERE id = ?').bind(id).first<{ id: string; snapshot_seq: number; reviewed_at: number | null }>();
  if (!briefing) throw notFound();
  if (briefing.reviewed_at !== null) throw new HttpError(409, 'already_reviewed', 'This briefing was already marked reviewed.');
  await db.batch([
    db.prepare('UPDATE briefings SET reviewed_at = ? WHERE id = ? AND reviewed_at IS NULL').bind(now, id),
    db.prepare(
      `INSERT INTO review_state(id, last_meaningful_at, last_reviewed_seq, review_epoch) VALUES ('owner', ?, ?, 'v1')
       ON CONFLICT(id) DO UPDATE SET last_meaningful_at = excluded.last_meaningful_at, last_reviewed_seq = MAX(review_state.last_reviewed_seq, excluded.last_reviewed_seq)`,
    ).bind(now, briefing.snapshot_seq),
  ]);
  return { id, reviewedThroughSeq: briefing.snapshot_seq, reviewedAt: new Date(now).toISOString() };
}
