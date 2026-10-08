import { BODY_GENERATION } from '../archive/publish';
import { resolvePartition } from '../archive/partitions';
import type { Env } from '../env';

/** Retrieval bounds (lld.md#chat): at most 20 search candidates and 4 prompt chunks per inference. */
export const MAX_CANDIDATES = 20;
export const MAX_PROMPT_CHUNKS = 4;
const MAX_TERMS = 12;

export type ScopeKind = 'all' | 'concern' | 'source' | 'service';
export interface Scope {
  kind: ScopeKind;
  id?: string;
}

export interface Evidence {
  chunkId: string;
  sourceId: string;
  attachmentId: string | null;
  page: number | null;
  text: string;
  hash: string;
  subject: string;
  receivedAt: number;
  privacy: string;
  sourceRevision: number;
}

const STOPWORDS = new Set(
  'the and for are but not you all any can had her was one our out has him his how its may new now old see two way who did get let put say she too use what when where which while with this that from have they will your about would there their them then than been were into more some such only also just like over most here does into why whom whose'.split(
    ' ',
  ),
);

/** Literal FTS5 phrases joined by OR, from the question's words. No operators or column filters from the text. */
export function questionQuery(question: string): string | null {
  const words = (question.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu) ?? []).filter((w) => !STOPWORDS.has(w));
  const terms = [...new Set(words)].slice(0, MAX_TERMS);
  return terms.length === 0 ? null : terms.map((t) => `"${t.replace(/"/g, '""')}"`).join(' OR ');
}

/** Source IDs a scope covers, or null for the whole archive. */
export async function scopeSourceIds(db: D1Database, scope: Scope): Promise<string[] | null> {
  if (scope.kind === 'all') return null;
  if (scope.kind === 'source') return [scope.id!];
  const sql =
    scope.kind === 'concern'
      ? 'SELECT source_id FROM concern_sources WHERE concern_id = ?'
      : 'SELECT cs.source_id FROM concern_sources cs JOIN concerns c ON c.id = cs.concern_id WHERE c.service_id = ?';
  return (await db.prepare(sql).bind(scope.id).all<{ source_id: string }>()).results.map((r) => r.source_id);
}

/** D1 allows 100 bound parameters per statement; lists are read in slices of at most this many. */
const BIND_LIMIT = 90;

function slices<T>(items: readonly T[]): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += BIND_LIMIT) out.push(items.slice(i, i + BIND_LIMIT));
  return out;
}

interface Eligible {
  privacy: string;
  revision: number;
}

/** Current control authority: ready and admitted sources, by default only those a model may read. */
async function eligibleSources(db: D1Database, ids: string[] | null, modelOnly = true): Promise<Map<string, Eligible> | null> {
  if (ids === null) return null;
  const out = new Map<string, Eligible>();
  for (const slice of slices(ids)) {
    const { results } = await db
      .prepare(`SELECT id, privacy, revision FROM source_registry WHERE id IN (${slice.map(() => '?').join(',')}) AND state = 'ready' AND admission = 'normal'${modelOnly ? " AND privacy IN ('hybrid', 'local_only')" : ''}`)
      .bind(...slice)
      .all<{ id: string; privacy: string; revision: number }>();
    for (const r of results) out.set(r.id, { privacy: r.privacy, revision: r.revision });
  }
  return out;
}

interface Row {
  chunk_id: string;
  source_id: string;
  attachment_id: string | null;
  generation_id: string;
  page: number | null;
  text: string;
  text_hash: string;
  subject: string;
  received_at: number;
}

async function publishedGenerations(db: D1Database, attachmentIds: string[]): Promise<Set<string>> {
  const out = new Set<string>();
  for (const slice of slices(attachmentIds)) {
    const { results } = await db
      .prepare(`SELECT attachment_id, active_generation FROM attachment_processing WHERE attachment_id IN (${slice.map(() => '?').join(',')}) AND active_generation IS NOT NULL`)
      .bind(...slice)
      .all<{ attachment_id: string; active_generation: string }>();
    for (const r of results) out.add(`${r.attachment_id}\n${r.active_generation}`);
  }
  return out;
}

/** Keeps rows whose source is eligible now and whose text belongs to a published generation. */
async function authorize(db: D1Database, rows: Row[], scoped: Map<string, Eligible> | null): Promise<Evidence[]> {
  const authority = scoped ?? (await eligibleSources(db, [...new Set(rows.map((r) => r.source_id))]))!;
  const published = await publishedGenerations(db, [...new Set(rows.flatMap((r) => (r.attachment_id ? [r.attachment_id] : [])))]);
  return rows.flatMap((r) => {
    const source = authority.get(r.source_id);
    const visible = r.attachment_id === null ? r.generation_id === BODY_GENERATION : published.has(`${r.attachment_id}\n${r.generation_id}`);
    if (!source || !visible) return [];
    return [{ chunkId: r.chunk_id, sourceId: r.source_id, attachmentId: r.attachment_id, page: r.page, text: r.text, hash: r.text_hash, subject: r.subject, receivedAt: r.received_at, privacy: source.privacy, sourceRevision: source.revision }];
  });
}

async function partitions(env: Env): Promise<D1Database[]> {
  const { results } = await env.CONTROL_DB.prepare('SELECT binding_name FROM archive_partitions ORDER BY id LIMIT 4').all<{ binding_name: string }>();
  return results.flatMap((p) => {
    const db = resolvePartition(env, p.binding_name);
    return db ? [db] : [];
  });
}

const SELECT = `SELECT c.id AS chunk_id, c.source_id, c.attachment_id, c.generation_id, c.page, c.text, c.text_hash, s.subject, s.received_at`;

/**
 * Deterministic text retrieval in scope (P1): FTS candidates for the question's words, post-filtered against
 * current control state, then the best four. A scoped conversation with no matching words falls back to the
 * newest scoped sources' first chunks. Quarantined, deleted, no-AI and restricted sources never qualify.
 */
export async function retrieve(env: Env, scope: Scope, question: string): Promise<Evidence[]> {
  const db = env.CONTROL_DB;
  const ids = await scopeSourceIds(db, scope);
  const scoped = await eligibleSources(db, ids);
  if (scoped && scoped.size === 0) return [];
  const query = questionQuery(question);
  const restrict = scoped ? [...scoped.keys()].slice(0, 90) : null;
  const evidence: Evidence[] = [];
  for (const archive of await partitions(env)) {
    if (evidence.length >= MAX_PROMPT_CHUNKS) break;
    let rows: Row[] = [];
    if (query) {
      rows = (
        await archive
          .prepare(
            `${SELECT} FROM source_fts f JOIN chunks c ON c.id = f.chunk_id JOIN sources s ON s.id = f.source_id
             WHERE source_fts MATCH ?${restrict ? ` AND f.source_id IN (${restrict.map(() => '?').join(',')})` : ''}
             ORDER BY f.rank, s.received_at DESC, c.id LIMIT ?`,
          )
          .bind(query, ...(restrict ?? []), MAX_CANDIDATES)
          .all<Row>()
      ).results;
    }
    if (rows.length === 0 && restrict) {
      rows = (
        await archive
          .prepare(`${SELECT} FROM chunks c JOIN sources s ON s.id = c.source_id WHERE c.source_id IN (${restrict.map(() => '?').join(',')}) AND c.attachment_id IS NULL ORDER BY s.received_at DESC, c.ordinal LIMIT ?`)
          .bind(...restrict, MAX_CANDIDATES)
          .all<Row>()
      ).results;
    }
    evidence.push(...(await authorize(db, rows, scoped)));
  }
  return evidence.slice(0, MAX_PROMPT_CHUNKS);
}

/**
 * Re-reads exactly these chunks under current control state, for a device claim, a result check or (with
 * `modelOnly: false`, so no-AI sources stay viewable by the owner) a turn view.
 * Chunk IDs start with their source ID (body) or attachment ID (attachment text). Missing or no longer
 * eligible chunks are simply absent from the result.
 */
export async function loadEvidence(env: Env, chunkIds: string[], options: { modelOnly: boolean } = { modelOnly: true }): Promise<Evidence[]> {
  if (chunkIds.length === 0) return [];
  const db = env.CONTROL_DB;
  const wanted = [...new Set(chunkIds)];
  const heads = [...new Set(wanted.map((id) => id.slice(0, 36)))];
  const owners = new Map<string, string>(heads.map((h) => [h, h]));
  for (const slice of slices(heads)) {
    const { results: attachments } = await db
      .prepare(`SELECT attachment_id, source_id FROM attachment_processing WHERE attachment_id IN (${slice.map(() => '?').join(',')})`)
      .bind(...slice)
      .all<{ attachment_id: string; source_id: string }>();
    for (const a of attachments) owners.set(a.attachment_id, a.source_id);
  }
  const scoped = (await eligibleSources(db, [...new Set(owners.values())], options.modelOnly))!;
  const rows: Row[] = [];
  for (const archive of await partitions(env)) {
    for (const slice of slices(wanted)) {
      const { results } = await archive.prepare(`${SELECT} FROM chunks c JOIN sources s ON s.id = c.source_id WHERE c.id IN (${slice.map(() => '?').join(',')})`).bind(...slice).all<Row>();
      rows.push(...results);
    }
  }
  const found = new Map((await authorize(db, rows, scoped)).map((e) => [e.chunkId, e]));
  return chunkIds.flatMap((id) => (found.has(id) ? [found.get(id)!] : []));
}
