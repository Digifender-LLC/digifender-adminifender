import { CONCERN_COLUMNS, type ConcernRow } from '../concerns/model';
import { writeConcern } from '../concerns/store';
import { assertion } from '../db/assert';
import type { Env } from '../env';
import { notFound } from '../http/errors';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow } from '../sources/registry';
import { archiveMetadata } from '../sources/routes';

export interface DeletionPlan {
  sourceIds: string[];
  preconditions: { sources: Record<string, number>; concerns: Record<string, number> };
  preview: Record<string, unknown>;
  /** Concerns whose every live source is deleted: erased with their history. */
  erase: string[];
  /** Concerns that keep other evidence: the deleted links are removed and the title is rebuilt from what remains. */
  detach: { row: ConcernRow; remaining: string[]; removed: string[] }[];
}

const marks = (n: number) => Array.from({ length: n }, () => '?').join(',');

/** Exact sources, derived references and expected count for an archive deletion review (lld.md#deletion). */
export async function planArchiveDeletion(env: Env, sourceIds: string[]): Promise<DeletionPlan> {
  const db = env.CONTROL_DB;
  const { results: sources } = await db
    .prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id IN (${marks(sourceIds.length)}) AND r.state NOT IN ('deleting', 'deleted')`)
    .bind(...sourceIds)
    .all<RegistryRow>();
  if (sources.length !== sourceIds.length) throw notFound();
  const metadata = await archiveMetadata(env, sources);
  const attachments = await db.prepare(`SELECT COUNT(*) AS n FROM attachment_processing WHERE source_id IN (${marks(sourceIds.length)})`).bind(...sourceIds).first<{ n: number }>();
  const { results: links } = await db
    .prepare(
      `SELECT cs.concern_id, cs.source_id FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id
       WHERE cs.concern_id IN (SELECT concern_id FROM concern_sources WHERE source_id IN (${marks(sourceIds.length)})) AND r.state NOT IN ('deleting', 'deleted')
       ORDER BY r.received_at, r.id`,
    )
    .bind(...sourceIds)
    .all<{ concern_id: string; source_id: string }>();
  const byConcern = new Map<string, string[]>();
  for (const link of links) byConcern.set(link.concern_id, [...(byConcern.get(link.concern_id) ?? []), link.source_id]);
  const concernIds = [...byConcern.keys()].sort();
  const concerns = concernIds.length
    ? (await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id IN (${marks(concernIds.length)})`).bind(...concernIds).all<ConcernRow>()).results
    : [];
  const deleting = new Set(sourceIds);
  const erase: string[] = [];
  const detach: DeletionPlan['detach'] = [];
  for (const row of concerns) {
    const members = byConcern.get(row.id) ?? [];
    const remaining = members.filter((s) => !deleting.has(s));
    if (remaining.length === 0) erase.push(row.id);
    else detach.push({ row, remaining, removed: members.filter((s) => deleting.has(s)) });
  }
  return {
    sourceIds,
    preconditions: {
      sources: Object.fromEntries(sources.map((s) => [s.id, s.revision])),
      concerns: Object.fromEntries(concerns.map((c) => [c.id, c.revision])),
    },
    preview: {
      sources: sources.map((s) => ({ id: s.id, subject: metadata.get(s.id)?.subject ?? null, receivedAt: new Date(s.received_at).toISOString(), admission: s.admission })),
      attachments: attachments?.n ?? 0,
      concernsErased: erase.sort(),
      concernsDetached: detach.map((d) => d.row.id).sort(),
      derived: ['archive text and chunks', 'search index', 'attachments and extracted text', 'raw message', 'triage results', 'export files that include these sources'],
      expectedCount: sources.length,
    },
    erase,
    detach,
  };
}

/**
 * The confirming control transaction: registry `deleting`, revision, tombstones and change records, the control
 * deletion ledger, a cleanup job per source, concern erasure or detachment, and export invalidation. All read,
 * search and attachment endpoints deny the sources once this commits.
 */
export async function archiveDeletionStatements(env: Env, plan: DeletionPlan, operationId: string, now: number): Promise<D1PreparedStatement[]> {
  const db = env.CONTROL_DB;
  const statements: D1PreparedStatement[] = [];
  for (const [id, revision] of Object.entries(plan.preconditions.sources)) {
    statements.push(
      assertion(db, "EXISTS (SELECT 1 FROM source_registry WHERE id = ? AND revision = ? AND state NOT IN ('deleting', 'deleted'))", id, revision),
      db.prepare("UPDATE source_registry SET state = 'deleting', deleted_at = ?, revision = revision + 1 WHERE id = ?").bind(now, id),
      db.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) SELECT 'source', id, revision, 1, '{}', ? FROM source_registry WHERE id = ?").bind(now, id),
      db.prepare("INSERT INTO deletion_ledger(id, source_id, deleted_at, state, targets_json) VALUES (?, ?, ?, 'pending', ?)").bind(crypto.randomUUID(), id, now, JSON.stringify({ operationId })),
      db.prepare("UPDATE jobs SET state = 'cancelled', error_code = 'deleted' WHERE source_id = ? AND state = 'pending' AND kind <> 'cleanup'").bind(id),
      // Undispatched phone work is withdrawn; a late device result then fails its lease check.
      db.prepare("UPDATE inference_attempts SET state = 'cancelled', error_code = 'deleted', completed_at = ? WHERE source_id = ? AND state IN ('queued', 'leased')").bind(now, id),
      db.prepare(
        "INSERT OR IGNORE INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) VALUES (?, ?, 'cleanup', ?, NULL, 'pending', ?, ?, ?)",
      ).bind(crypto.randomUUID(), `cleanup:${id}`, id, now, JSON.stringify({ sourceId: id }), now),
      db.prepare("INSERT OR IGNORE INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, 'job:' || id, 'job', id, 'pending', ? FROM jobs WHERE dedupe_key = ?")
        .bind(crypto.randomUUID(), now, `cleanup:${id}`),
      // Export objects built from a snapshot that included this source are invalid immediately.
      db.prepare("UPDATE exports SET state = 'invalidated' WHERE state IN ('pending', 'running', 'ready') AND snapshot_seq >= COALESCE((SELECT ready_seq FROM source_registry WHERE id = ?), 0)").bind(id),
    );
  }
  for (const concernId of plan.erase) {
    statements.push(
      assertion(db, 'EXISTS (SELECT 1 FROM concerns WHERE id = ? AND revision = ?)', concernId, plan.preconditions.concerns[concernId]),
      db.prepare('DELETE FROM concern_sources WHERE concern_id = ?').bind(concernId),
      db.prepare('DELETE FROM concern_events WHERE concern_id = ?').bind(concernId),
      // Earlier projections carried the title; only the tombstone remains.
      db.prepare("UPDATE changes SET projection_json = '{}' WHERE entity_type = 'concern' AND entity_id = ?").bind(concernId),
      db.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) SELECT 'concern', id, revision + 1, 1, '{}', ? FROM concerns WHERE id = ?").bind(now, concernId),
      db.prepare('DELETE FROM concerns WHERE id = ?').bind(concernId),
    );
  }
  for (const item of plan.detach) {
    const title = await titleFrom(env, item.remaining[0] as string);
    const after: ConcernRow = { ...item.row, revision: item.row.revision + 1, title, updated_at: now };
    statements.push(
      ...writeConcern(db, { before: item.row, after, kind: 'evidence_deleted', eventId: crypto.randomUUID(), sourceIds: item.remaining, removedSourceIds: item.removed, now, meaningful: false }),
      // History keeps its shape but no longer carries a title that may quote deleted mail.
      db.prepare("UPDATE concern_events SET before_json = CASE WHEN json_type(before_json, '$.title') IS NULL THEN before_json ELSE json_set(before_json, '$.title', ?) END, after_json = json_set(after_json, '$.title', ?) WHERE concern_id = ?").bind(title, title, item.row.id),
      db.prepare("UPDATE changes SET projection_json = json_set(projection_json, '$.title', ?) WHERE entity_type = 'concern' AND entity_id = ? AND json_valid(projection_json)").bind(title, item.row.id),
    );
  }
  return statements;
}

async function titleFrom(env: Env, sourceId: string): Promise<string> {
  const row = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(sourceId).first<RegistryRow>();
  const subject = row ? (await archiveMetadata(env, [row])).get(row.id)?.subject : undefined;
  return subject?.trim().slice(0, 200) || '(no subject)';
}
