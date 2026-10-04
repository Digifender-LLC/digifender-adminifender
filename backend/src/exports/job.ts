import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';
import { sha256Hex } from '../ids';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { ExportTooLarge, MultipartSink, ZipWriter } from './zip-writer';

/** Completed export objects are kept 7 days (baseline.md defaults). */
export const EXPORT_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const EXPORT_SCHEMA_VERSION = 1;

interface ExportRow {
  id: string;
  snapshot_seq: number;
  state: string;
  manifest_key: string | null;
  object_key: string | null;
  created_at: number;
  expires_at: number;
}

const encoder = new TextEncoder();

/** Ends the job early with a result after the partial upload is aborted. */
class Stop extends Error {
  constructor(readonly result: JobResult) {
    super(result.kind);
  }
}
const jsonl = (rows: unknown[]) => encoder.encode(rows.map((r) => JSON.stringify(r)).join('\n') + (rows.length ? '\n' : ''));
const safeName = (name: string) => name.replace(/[\u0000-\u001f\u007f"\\/:*?<>|]/g, '_').slice(0, 120) || 'attachment';
const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

export const exportBody = v.object({});

// POST /exports — captures the change-sequence snapshot and queues a replay-safe export job.
export async function createExport(env: Env, now: number) {
  const db = env.CONTROL_DB;
  const id = crypto.randomUUID();
  const jobId = crypto.randomUUID();
  const snapshot = (await db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
  await db.batch([
    db.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at) VALUES (?, ?, 'pending', ?, ?)").bind(id, snapshot, now, now + EXPORT_TTL_MS),
    db.prepare("INSERT INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) VALUES (?, ?, 'export', NULL, NULL, 'pending', ?, ?, ?)")
      .bind(jobId, `export:${id}`, now, JSON.stringify({ exportId: id }), now),
    db.prepare("INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) VALUES (?, ?, 'job', ?, 'pending', ?)").bind(crypto.randomUUID(), `job:${jobId}`, jobId, now),
  ]);
  return { id, state: 'pending', snapshotSeq: snapshot, expiresAt: iso(now + EXPORT_TTL_MS) };
}

async function loadExport(env: Env, id: string): Promise<ExportRow> {
  const row = await env.CONTROL_DB.prepare('SELECT * FROM exports WHERE id = ?').bind(id).first<ExportRow>();
  if (!row) throw notFound();
  return row;
}

// GET /exports/{id}
export async function getExport(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const row = await loadExport(env, v.uuid()(id, '$.id'));
  const expired = row.expires_at <= now;
  const manifest = row.manifest_key && !expired && row.state === 'ready' ? ((await (await env.ARCHIVE_BUCKET.get(row.manifest_key))?.json()) as { counts: unknown; omissions: unknown } | null) : null;
  return {
    id: row.id,
    state: expired && row.state === 'ready' ? 'expired' : row.state,
    snapshotSeq: row.snapshot_seq,
    createdAt: iso(row.created_at),
    expiresAt: iso(row.expires_at),
    counts: manifest?.counts ?? null,
    omissions: manifest?.omissions ?? null,
  };
}

// GET /exports/{id}/content — authenticated ZIP stream; deletion after the snapshot invalidates it.
export async function getExportContent(request: Request, env: Env, now: number, [id]: string[]): Promise<Response> {
  await authenticate(request, env, now);
  const row = await loadExport(env, v.uuid()(id, '$.id'));
  if (row.state === 'invalidated') throw new HttpError(410, 'deleted', 'Content in this export was deleted. Create a new export.');
  if (row.expires_at <= now || row.state === 'expired') throw new HttpError(410, 'expired', 'This export expired. Create a new export.');
  if (row.state !== 'ready' || !row.object_key) throw new HttpError(409, 'not_ready', 'The export is still being prepared.', {}, true);
  const object = await env.ARCHIVE_BUCKET.get(row.object_key);
  if (!object) throw new HttpError(410, 'expired', 'This export is no longer available.');
  const day = new Date(row.created_at).toISOString().slice(0, 10);
  return new Response(object.body, {
    headers: {
      'content-type': 'application/zip',
      'content-length': String(object.size),
      'content-disposition': `attachment; filename="adminifender-export-${day}.zip"`,
      'cache-control': 'no-store',
      'x-content-type-options': 'nosniff',
    },
  });
}

/** Latest projection of each live entity of one type at the snapshot, as bootstrap pages them. */
async function snapshotProjections(db: D1Database, type: string, snapshot: number): Promise<unknown[]> {
  const { results } = await db
    .prepare(
      `SELECT c.projection_json FROM changes c WHERE c.entity_type = ? AND c.seq <= ? AND c.deleted = 0
         AND NOT EXISTS (SELECT 1 FROM changes n WHERE n.entity_type = c.entity_type AND n.entity_id = c.entity_id AND n.seq > c.seq AND n.seq <= ?)
       ORDER BY c.seq`,
    )
    .bind(type, snapshot, snapshot)
    .all<{ projection_json: string }>();
  return results.map((r) => JSON.parse(r.projection_json) as unknown);
}

/**
 * Builds the export ZIP (lld.md#exports): sources.jsonl, attachments.jsonl, concerns.jsonl, rules.jsonl,
 * services.jsonl, files/... and manifest.json with hashes and omissions. Sources come from ready projections at
 * the snapshot; current deletion is checked again as each is written, so a later deletion is listed as omitted and
 * never resurrected. Secrets, sessions and signing material are never read.
 */
export async function exportJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  const exportId = (job.input as { exportId?: unknown }).exportId;
  if (typeof exportId !== 'string') return { kind: 'fail', errorCode: 'invalid_input' };
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT * FROM exports WHERE id = ?').bind(exportId).first<ExportRow>();
  if (!row || !['pending', 'running'].includes(row.state)) return { kind: 'complete', output: { skipped: row?.state ?? 'missing' } };
  await db.prepare("UPDATE exports SET state = 'running' WHERE id = ? AND state = 'pending'").bind(exportId).run();

  const objectKey = `exports/${exportId}/export.zip`;
  const manifestKey = `exports/${exportId}/manifest.json`;
  const upload = await env.ARCHIVE_BUCKET.createMultipartUpload(objectKey, { httpMetadata: { contentType: 'application/zip' } });
  const sink = new MultipartSink(upload);
  const zip = new ZipWriter(sink.write, new Date(row.created_at));
  const files: { path: string; sha256: string; bytes: number }[] = [];
  const add = async (path: string, data: Uint8Array) => {
    await zip.add(path, data);
    files.push({ path, sha256: await sha256Hex(data), bytes: data.length });
  };

  try {
    const { results: candidates } = await db
      .prepare(`SELECT ${REGISTRY_COLUMNS}, r.ready_seq, r.staged_key FROM ${REGISTRY_FROM} WHERE r.ready_seq IS NOT NULL AND r.ready_seq <= ? ORDER BY r.received_at, r.id`)
      .bind(row.snapshot_seq)
      .all<RegistryRow & { ready_seq: number; staged_key: string | null }>();
    const sources: unknown[] = [];
    const attachmentsOut: unknown[] = [];
    const omissions: { sourceId: string; reason: string }[] = [];
    for (const candidate of candidates) {
      const current = await db.prepare('SELECT state FROM source_registry WHERE id = ?').bind(candidate.id).first<{ state: string }>();
      if (current?.state !== 'ready') {
        omissions.push({ sourceId: candidate.id, reason: 'deleted' });
        continue;
      }
      const archive = archiveFor(env, candidate);
      const stored = archive
        ? await archive.prepare('SELECT subject, envelope_json, headers_json, normalized_text, text_object_key, text_hash, provenance_json, parser_version FROM sources WHERE id = ?').bind(candidate.id).first<{
            subject: string;
            envelope_json: string;
            headers_json: string;
            normalized_text: string | null;
            text_object_key: string | null;
            text_hash: string;
            provenance_json: string;
            parser_version: string;
          }>()
        : null;
      if (!archive || !stored) throw new Stop({ kind: 'retry', errorCode: 'archive_unavailable' });
      const text = stored.normalized_text ?? (stored.text_object_key ? ((await (await env.ARCHIVE_BUCKET.get(stored.text_object_key))?.text()) ?? null) : null);
      if (text === null) throw new Stop({ kind: 'retry', errorCode: 'text_unavailable' });
      let originalPath: string | null = null;
      if (candidate.staged_key) {
        const raw = await env.ARCHIVE_BUCKET.get(candidate.staged_key);
        if (raw) {
          originalPath = `files/${candidate.id}/message.eml`;
          await add(originalPath, new Uint8Array(await raw.arrayBuffer()));
        }
      }
      sources.push({
        id: candidate.id,
        kind: candidate.kind,
        receivedAt: iso(candidate.received_at),
        admission: candidate.admission,
        privacy: candidate.privacy,
        subject: stored.subject,
        envelope: JSON.parse(stored.envelope_json) as unknown,
        headers: JSON.parse(stored.headers_json) as unknown,
        text,
        textHash: stored.text_hash,
        parserVersion: stored.parser_version,
        provenance: JSON.parse(stored.provenance_json) as unknown,
        originalPath,
        originalStatus: originalPath ? 'included' : 'expired',
      });
      const { results: attachments } = await archive
        .prepare('SELECT id, name, declared_type, size_bytes, sha256, object_key, disposition FROM attachments WHERE source_id = ? ORDER BY name, id')
        .bind(candidate.id)
        .all<{ id: string; name: string; declared_type: string | null; size_bytes: number; sha256: string; object_key: string; disposition: string }>();
      for (const attachment of attachments) {
        const state = await db.prepare('SELECT state, detail FROM attachment_processing WHERE attachment_id = ?').bind(attachment.id).first<{ state: string; detail: string | null }>();
        const object = await env.ARCHIVE_BUCKET.get(attachment.object_key);
        if (!object) throw new Stop({ kind: 'retry', errorCode: 'attachment_unavailable' });
        const path = `files/${candidate.id}/${attachment.id}/${safeName(attachment.name)}`;
        const data = new Uint8Array(await object.arrayBuffer());
        if ((await sha256Hex(data)) !== attachment.sha256) throw new Stop({ kind: 'fail', errorCode: 'attachment_hash_mismatch' });
        await add(path, data);
        attachmentsOut.push({
          id: attachment.id,
          sourceId: candidate.id,
          name: attachment.name,
          declaredType: attachment.declared_type,
          sizeBytes: attachment.size_bytes,
          sha256: attachment.sha256,
          disposition: attachment.disposition,
          extraction: state?.state ?? 'stored',
          extractionDetail: state?.detail ?? null,
          path,
        });
      }
    }
    const included = new Set((sources as { id: string }[]).map((s) => s.id));
    const concerns = ((await snapshotProjections(db, 'concern', row.snapshot_seq)) as { sourceIds?: string[] }[])
      .map((c) => ({ ...c, sourceIds: (c.sourceIds ?? []).filter((s) => included.has(s)) }))
      .filter((c) => c.sourceIds.length > 0);
    const rules = (await db.prepare('SELECT id, revision, origin, enabled, predicate_json, effect_json, created_at FROM rules ORDER BY created_at, id').all<{
      id: string;
      revision: number;
      origin: string;
      enabled: number;
      predicate_json: string;
      effect_json: string;
      created_at: number;
    }>()).results.map((r) => ({ id: r.id, revision: r.revision, origin: r.origin, enabled: r.enabled === 1, predicate: JSON.parse(r.predicate_json) as unknown, effects: JSON.parse(r.effect_json) as unknown, createdAt: iso(r.created_at) }));
    const services = (await db.prepare('SELECT id, revision, name, aliases_json, created_at FROM service_catalog ORDER BY name, id').all<{ id: string; revision: number; name: string; aliases_json: string; created_at: number }>()).results.map((s) => ({
      id: s.id,
      revision: s.revision,
      name: s.name,
      aliases: JSON.parse(s.aliases_json) as unknown,
      createdAt: iso(s.created_at),
    }));
    await add('sources.jsonl', jsonl(sources));
    await add('attachments.jsonl', jsonl(attachmentsOut));
    await add('concerns.jsonl', jsonl(concerns));
    await add('rules.jsonl', jsonl(rules));
    await add('services.jsonl', jsonl(services));
    const manifest = {
      schemaVersion: EXPORT_SCHEMA_VERSION,
      exportId,
      snapshotSeq: row.snapshot_seq,
      createdAt: iso(row.created_at),
      counts: { sources: sources.length, attachments: attachmentsOut.length, concerns: concerns.length, rules: rules.length, services: services.length },
      omissions,
      files,
    };
    const manifestBytes = encoder.encode(JSON.stringify(manifest, null, 2));
    await zip.add('manifest.json', manifestBytes);
    await zip.finish();
    await sink.complete();
    await env.ARCHIVE_BUCKET.put(manifestKey, manifestBytes, { httpMetadata: { contentType: 'application/json' } });
  } catch (error) {
    await upload.abort().catch(() => undefined);
    if (error instanceof Stop) return error.result;
    if (error instanceof ExportTooLarge) {
      return { kind: 'fail', errorCode: 'export_too_large', statements: [db.prepare(`UPDATE exports SET state = 'failed' WHERE id = ? AND ${CLAIM_GUARD}`).bind(exportId, ...guardParams(job))] };
    }
    throw error;
  }
  const [guardId, guardToken] = guardParams(job);
  // A deletion that landed while the ZIP was being written has already invalidated this export; it stays invalid.
  return {
    kind: 'complete',
    output: { exportId },
    statements: [
      db.prepare(`UPDATE exports SET state = 'ready', object_key = ?, manifest_key = ? WHERE id = ? AND state = 'running' AND ${CLAIM_GUARD}`).bind(objectKey, manifestKey, exportId, guardId, guardToken),
      db.prepare(`UPDATE exports SET object_key = ?, manifest_key = ? WHERE id = ? AND state = 'invalidated' AND ${CLAIM_GUARD}`).bind(objectKey, manifestKey, exportId, guardId, guardToken),
    ],
  };
}

/** Scheduled: removes objects of expired and invalidated exports (deleted content is cleaned within 24 hours). */
export async function expireExports(env: Pick<Env, 'CONTROL_DB' | 'ARCHIVE_BUCKET'>, now = Date.now()): Promise<{ removed: number }> {
  const { results } = await env.CONTROL_DB.prepare(
    "SELECT id, object_key, manifest_key, state FROM exports WHERE object_key IS NOT NULL AND (state = 'invalidated' OR expires_at <= ?) LIMIT 50",
  )
    .bind(now)
    .all<{ id: string; object_key: string; manifest_key: string | null; state: string }>();
  for (const row of results) {
    await env.ARCHIVE_BUCKET.delete([row.object_key, ...(row.manifest_key ? [row.manifest_key] : [])]);
    await env.CONTROL_DB.prepare("UPDATE exports SET object_key = NULL, manifest_key = NULL, state = CASE WHEN state = 'invalidated' THEN state ELSE 'expired' END WHERE id = ?").bind(row.id).run();
  }
  return { removed: results.length };
}
