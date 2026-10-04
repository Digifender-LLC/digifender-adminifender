import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { sha256Hex } from '../ids';
import { resolvePartition } from '../archive/partitions';
import { openCursor, sealCursor } from '../http/cursor';
import { HttpError } from '../http/errors';
import { v } from '../http/validate';
import { instant, pageSize, rejectUnknownParams } from '../sources/routes';
import { BODY_GENERATION } from '../archive/publish';
import { ftsQuery } from './query';

/** At most four partitions are visited per page; the rest continue through the cursor (lld.md#search). */
export const PARTITIONS_PER_PAGE = 4;
const BATCH = 100;

interface SearchCursor {
  snapshot: number;
  partitions: number[];
  index: number;
  offset: number;
}

interface Hit {
  source_id: string;
  chunk_id: string;
  generation_id: string;
  attachment_id: string | null;
  snippet: string;
  rank: number;
  received_at: number;
  subject: string;
  page: number | null;
  start_offset: number | null;
}

// GET /search
export async function search(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['q', 'view', 'sender', 'from', 'to', 'mode', 'limit', 'cursor']);
  const mode = url.searchParams.has('mode') ? v.oneOf(['exact', 'semantic'] as const)(url.searchParams.get('mode'), '$.mode') : 'exact';
  if (mode === 'semantic') throw new HttpError(403, 'feature_disabled', 'Semantic search is not enabled.');
  const match = ftsQuery(url.searchParams.get('q') ?? '');
  const view = url.searchParams.has('view') ? v.oneOf(['archive', 'quarantine'] as const)(url.searchParams.get('view'), '$.view') : 'archive';
  const sender = url.searchParams.has('sender') ? v.string({ max: 320 })(url.searchParams.get('sender'), '$.sender').trim().toLowerCase() : null;
  const from = instant(url, 'from');
  const to = instant(url, 'to');
  const limit = pageSize(url);

  const digest = await sha256Hex(JSON.stringify(['search', match, view, sender, from, to]));
  const cursorParam = url.searchParams.get('cursor');
  let cursor: SearchCursor;
  if (cursorParam) {
    cursor = await openCursor<SearchCursor>(env.CURSOR_SECRET, digest, cursorParam, now);
  } else {
    const snapshot = (await env.CONTROL_DB.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
    const partitions = (await env.CONTROL_DB.prepare('SELECT id FROM archive_partitions ORDER BY id').all<{ id: number }>()).results.map((p) => p.id);
    cursor = { snapshot, partitions, index: 0, offset: 0 };
  }

  const results: (Hit & { partitionId: number })[] = [];
  let visited = 0;
  while (results.length < limit && cursor.index < cursor.partitions.length && visited < PARTITIONS_PER_PAGE) {
    const partitionId = cursor.partitions[cursor.index] as number;
    const binding = await env.CONTROL_DB.prepare('SELECT binding_name FROM archive_partitions WHERE id = ?').bind(partitionId).first<{ binding_name: string }>();
    const db = binding ? resolvePartition(env, binding.binding_name) : null;
    if (!db) throw new HttpError(503, 'unavailable', 'Part of the archive is unavailable.', {}, true);
    const where = ['source_fts MATCH ?'];
    const params: unknown[] = [match];
    if (from !== null) (where.push('s.received_at >= ?'), params.push(from));
    if (to !== null) (where.push('s.received_at < ?'), params.push(to));
    if (sender) (where.push("EXISTS (SELECT 1 FROM json_each(s.envelope_json, '$.from') WHERE lower(value) = ?)"), params.push(sender));
    const { results: hits } = await db
      .prepare(
        `SELECT f.source_id, f.chunk_id, f.generation_id, c.attachment_id, snippet(source_fts, 4, '[', ']', '…', 16) AS snippet, f.rank AS rank, s.received_at, s.subject, c.page, c.start_offset
         FROM source_fts f JOIN sources s ON s.id = f.source_id LEFT JOIN chunks c ON c.id = f.chunk_id
         WHERE ${where.join(' AND ')} ORDER BY f.rank, s.received_at DESC, f.source_id, f.chunk_id LIMIT ? OFFSET ?`,
      )
      .bind(...params, BATCH, cursor.offset)
      .all<Hit>();

    // Post-filter against current control authority before anything leaves the service.
    const ids = [...new Set(hits.map((h) => h.source_id))];
    const allowed = new Set<string>();
    if (ids.length > 0) {
      const { results: rows } = await env.CONTROL_DB.prepare(
        `SELECT id FROM source_registry WHERE id IN (${ids.map(() => '?').join(',')}) AND state = 'ready' AND admission = ?`,
      )
        .bind(...ids, view === 'quarantine' ? 'quarantine' : 'normal')
        .all<{ id: string }>();
      for (const row of rows) allowed.add(row.id);
    }
    // Attachment text is visible only through its published generation (lld.md#text-extraction-and-ocr).
    const attachmentIds = [...new Set(hits.flatMap((h) => (h.attachment_id ? [h.attachment_id] : [])))];
    const published = new Set<string>();
    if (attachmentIds.length > 0) {
      const { results: rows } = await env.CONTROL_DB.prepare(
        `SELECT attachment_id, active_generation FROM attachment_processing WHERE attachment_id IN (${attachmentIds.map(() => '?').join(',')}) AND active_generation IS NOT NULL`,
      )
        .bind(...attachmentIds)
        .all<{ attachment_id: string; active_generation: string }>();
      for (const row of rows) published.add(`${row.attachment_id}\n${row.active_generation}`);
    }
    const visible = (hit: Hit) => allowed.has(hit.source_id) && (hit.attachment_id === null ? hit.generation_id === BODY_GENERATION : published.has(`${hit.attachment_id}\n${hit.generation_id}`));
    let consumed = 0;
    for (const hit of hits) {
      if (results.length >= limit) break;
      consumed += 1;
      if (visible(hit)) results.push({ ...hit, partitionId });
    }
    cursor = { ...cursor, offset: cursor.offset + consumed };
    if (hits.length < BATCH && consumed === hits.length) {
      cursor = { ...cursor, index: cursor.index + 1, offset: 0 };
      visited += 1;
    }
  }

  const more = cursor.index < cursor.partitions.length;
  return {
    results: results.map((r) => ({
      sourceId: r.source_id,
      chunkId: r.chunk_id,
      attachmentId: r.attachment_id,
      partitionId: r.partitionId,
      subject: r.subject,
      snippet: r.snippet,
      page: r.page,
      startOffset: r.start_offset,
      receivedAt: new Date(r.received_at).toISOString(),
    })),
    // Scores are partition-local; the order is not a global ranking until every partition has been read.
    partitionsRemaining: cursor.partitions.length - cursor.index,
    nextCursor: more ? await sealCursor(env.CURSOR_SECRET, digest, cursor, now) : null,
    snapshot: cursor.snapshot,
  };
}
