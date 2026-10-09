import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { sha256Hex } from '../ids';
import { openCursor, sealCursor } from '../http/cursor';
import { HttpError, notFound } from '../http/errors';
import { decideRange, rangeHeaders } from '../http/range';
import { resolvePartition } from '../archive/partitions';
import { v } from '../http/validate';
import { type RegistryRow, REGISTRY_COLUMNS, REGISTRY_FROM, archiveFor, sourceProjection, visibleSource } from './registry';

const DEFAULT_PAGE = 50;
const MAX_PAGE = 200;

export function pageSize(url: URL): number {
  const raw = url.searchParams.get('limit');
  if (raw === null) return DEFAULT_PAGE;
  return v.integer({ min: 1, max: MAX_PAGE })(Number(raw), '$.limit');
}

export function instant(url: URL, name: string): number | null {
  const raw = url.searchParams.get(name);
  if (raw === null) return null;
  const value = Date.parse(v.string({ max: 40, pattern: /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,3})?Z$/ })(raw, `$.${name}`));
  if (Number.isNaN(value)) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path: `$.${name}`, reason: 'format' });
  return value;
}

export function rejectUnknownParams(url: URL, allowed: string[]): void {
  for (const key of url.searchParams.keys()) {
    if (!allowed.includes(key)) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path: `$.${key}`, reason: 'unknown_field' });
  }
}

export async function archiveMetadata(env: Env, rows: RegistryRow[]): Promise<Map<string, { subject: string; envelope_json: string }>> {
  const byPartition = new Map<string, string[]>();
  for (const row of rows) if (row.binding_name) byPartition.set(row.binding_name, [...(byPartition.get(row.binding_name) ?? []), row.id]);
  const out = new Map<string, { subject: string; envelope_json: string }>();
  for (const [binding, ids] of byPartition) {
    const db = resolvePartition(env, binding);
    if (!db) continue;
    const { results } = await db.prepare(`SELECT id, subject, envelope_json FROM sources WHERE id IN (${ids.map(() => '?').join(',')})`).bind(...ids).all<{ id: string; subject: string; envelope_json: string }>();
    for (const result of results) out.set(result.id, result);
  }
  return out;
}

// GET /sources
export async function listSources(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['admission', 'kind', 'state', 'from', 'to', 'limit', 'cursor']);
  const filters = {
    admission: url.searchParams.has('admission') ? v.oneOf(['normal', 'quarantine'] as const)(url.searchParams.get('admission'), '$.admission') : null,
    kind: url.searchParams.has('kind') ? v.oneOf(['email'] as const)(url.searchParams.get('kind'), '$.kind') : null,
    state: url.searchParams.has('state') ? v.oneOf(['staged', 'pending_index', 'ready', 'parse_failed'] as const)(url.searchParams.get('state'), '$.state') : null,
    from: instant(url, 'from'),
    to: instant(url, 'to'),
  };
  const limit = pageSize(url);
  const digest = await sha256Hex(JSON.stringify(['sources', filters]));
  const cursorParam = url.searchParams.get('cursor');
  const position = cursorParam ? await openCursor<{ receivedAt: number; id: string; snapshot: number }>(env.CURSOR_SECRET, digest, cursorParam, now) : null;
  const snapshot = position?.snapshot ?? ((await env.CONTROL_DB.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0);

  const where = ["r.state NOT IN ('deleting', 'deleted')"];
  const params: unknown[] = [];
  if (filters.admission) (where.push('r.admission = ?'), params.push(filters.admission));
  if (filters.kind) (where.push('r.kind = ?'), params.push(filters.kind));
  if (filters.state) (where.push('r.state = ?'), params.push(filters.state));
  if (filters.from !== null) (where.push('r.received_at >= ?'), params.push(filters.from));
  if (filters.to !== null) (where.push('r.received_at < ?'), params.push(filters.to));
  if (position) (where.push('(r.received_at < ? OR (r.received_at = ? AND r.id < ?))'), params.push(position.receivedAt, position.receivedAt, position.id));
  const { results } = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE ${where.join(' AND ')} ORDER BY r.received_at DESC, r.id DESC LIMIT ?`)
    .bind(...params, limit + 1)
    .all<RegistryRow>();
  const page = results.slice(0, limit);
  const metadata = await archiveMetadata(env, page);
  const last = page[page.length - 1];
  const nextCursor = results.length > limit && last ? await sealCursor(env.CURSOR_SECRET, digest, { receivedAt: last.received_at, id: last.id, snapshot }, now) : null;
  return { sources: page.map((row) => sourceProjection(row, metadata.get(row.id) ?? null)), nextCursor, snapshot };
}

// GET /sources/{id}
export async function getSource(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const row = await visibleSource(env, v.uuid()(id, '$.id'));
  const archive = archiveFor(env, row);
  const stored = archive
    ? await archive.prepare('SELECT subject, envelope_json, normalized_text, text_object_key, provenance_json, parser_version FROM sources WHERE id = ?').bind(row.id).first<{
        subject: string;
        envelope_json: string;
        normalized_text: string | null;
        text_object_key: string | null;
        provenance_json: string;
        parser_version: string;
      }>()
    : null;
  const attachments = archive
    ? (await archive.prepare('SELECT id, name, declared_type, size_bytes, sha256, disposition FROM attachments WHERE source_id = ? ORDER BY name, id').bind(row.id).all<{
        id: string;
        name: string;
        declared_type: string | null;
        size_bytes: number;
        sha256: string;
        disposition: string;
      }>()).results
    : [];
  const processing = new Map(
    (await env.CONTROL_DB.prepare('SELECT attachment_id, state, detail FROM attachment_processing WHERE source_id = ?').bind(row.id).all<{ attachment_id: string; state: string; detail: string | null }>()).results.map((p) => [p.attachment_id, p]),
  );
  return {
    source: sourceProjection(row, stored),
    // Bodies stored in R2 are read through the chunk endpoint; inline text is returned whole.
    text: stored?.normalized_text ?? null,
    textInChunks: stored ? stored.normalized_text === null : false,
    provenance: stored ? { ...(JSON.parse(stored.provenance_json) as object), parserVersion: stored.parser_version } : null,
    attachments: attachments.map((a) => ({
      id: a.id,
      name: a.name,
      declaredType: a.declared_type,
      sizeBytes: a.size_bytes,
      sha256: a.sha256,
      disposition: a.disposition,
      extraction: processing.get(a.id)?.state ?? 'stored',
      extractionDetail: processing.get(a.id)?.detail ?? null,
    })),
  };
}

// GET /sources/{id}/chunks
export async function getChunks(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['limit', 'cursor']);
  const row = await visibleSource(env, v.uuid()(id, '$.id'));
  const archive = archiveFor(env, row);
  if (!archive) return { chunks: [], nextCursor: null };
  const limit = pageSize(url);
  const digest = await sha256Hex(JSON.stringify(['chunks', row.id]));
  const cursorParam = url.searchParams.get('cursor');
  const after = cursorParam ? (await openCursor<{ index: number }>(env.CURSOR_SECRET, digest, cursorParam, now)).index : -1;
  // Only published generations are readable: the body generation plus each attachment's active generation.
  const active = (await env.CONTROL_DB.prepare('SELECT active_generation FROM attachment_processing WHERE source_id = ? AND active_generation IS NOT NULL').bind(row.id).all<{ active_generation: string }>()).results.map((r) => r.active_generation);
  const generations = ['body-v1', ...active];
  const { results } = await archive
    .prepare(
      `SELECT id, attachment_id, ordinal, page, start_offset, text FROM chunks WHERE source_id = ? AND generation_id IN (${generations.map(() => '?').join(',')})
       ORDER BY attachment_id IS NOT NULL, attachment_id, ordinal LIMIT ? OFFSET ?`,
    )
    .bind(row.id, ...generations, limit + 1, after + 1)
    .all<{ id: string; attachment_id: string | null; ordinal: number; page: number | null; start_offset: number | null; text: string }>();
  const page = results.slice(0, limit);
  const nextCursor = results.length > limit ? await sealCursor(env.CURSOR_SECRET, digest, { index: after + limit }, now) : null;
  return {
    chunks: page.map((c) => ({ chunkId: c.id, attachmentId: c.attachment_id, ordinal: c.ordinal, page: c.page, startOffset: c.start_offset, text: c.text })),
    nextCursor,
  };
}

function safeFilename(name: string): string {
  const cleaned = name.replace(/[\u0000-\u001f\u007f"\\/]/g, '_').slice(0, 200) || 'attachment';
  return cleaned;
}

// GET /attachments/{id}/content
export async function getAttachmentContent(request: Request, env: Env, now: number, [id]: string[]): Promise<Response> {
  await authenticate(request, env, now);
  const attachmentId = v.uuid()(id, '$.id');
  const { results: partitions } = await env.CONTROL_DB.prepare('SELECT binding_name FROM archive_partitions ORDER BY id').all<{ binding_name: string }>();
  for (const partition of partitions) {
    const db = resolvePartition(env, partition.binding_name);
    const attachment = await db?.prepare('SELECT source_id, name, object_key, size_bytes, sha256 FROM attachments WHERE id = ?').bind(attachmentId).first<{
      source_id: string;
      name: string;
      object_key: string;
      size_bytes: number;
      sha256: string;
    }>();
    if (!attachment) continue;
    await visibleSource(env, attachment.source_id);
    // A ranged request looks the size up first; a plain one reads the object once, as before.
    const size = request.headers.has('range') ? (await env.ARCHIVE_BUCKET.head(attachment.object_key))?.size : attachment.size_bytes;
    if (size === undefined) throw new HttpError(503, 'unavailable', 'The file is temporarily unavailable.', {}, true);
    const decision = decideRange(request, size);
    const name = safeFilename(attachment.name);
    const common = {
      'content-type': 'application/octet-stream',
      'content-disposition': `attachment; filename="${name.replace(/[^\x20-\x7e]/g, '_')}"; filename*=UTF-8''${encodeURIComponent(name)}`,
      'x-content-type-options': 'nosniff',
      'cache-control': 'private, no-store',
      // The hash is of the whole file, also on a slice of it.
      'x-attachment-sha256': attachment.sha256,
    };
    if (decision.kind === 'unsatisfiable') return new Response(null, { status: 416, headers: { ...common, ...rangeHeaders(decision, size) } });
    const object = decision.kind === 'partial' ? await env.ARCHIVE_BUCKET.get(attachment.object_key, { range: { offset: decision.offset, length: decision.length } }) : await env.ARCHIVE_BUCKET.get(attachment.object_key);
    if (!object) throw new HttpError(503, 'unavailable', 'The file is temporarily unavailable.', {}, true);
    return new Response(object.body, { status: decision.kind === 'partial' ? 206 : 200, headers: { ...common, ...rangeHeaders(decision, size) } });
  }
  throw notFound();
}

