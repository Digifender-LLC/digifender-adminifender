import type { AuthContext } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { canonicalJson } from '../http/idempotency';
import { sha256Hex } from '../ids';
import { POLICY_REVISION_SQL, loadSettings } from '../inference/settings';
import { CLAIM_GUARD, type ClaimedJob, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { detectKind } from './text';
import {
  DEVICE_EXTRACTOR_VERSION, EXTRACTION_LEASE_MS, MAX_EXTRACTION_BATCHES, MAX_EXTRACTION_TEXT_BYTES,
  type ExtractionFence, type ExtractionPart, extractionBatchBody, extractionClaimBody, extractionDeferBody, extractionFinishBody, extractionHeartbeatBody, extractionResumeBody,
} from './device-contract';

export { extractionBatchBody, extractionClaimBody, extractionDeferBody, extractionFinishBody, extractionHeartbeatBody, extractionResumeBody };

interface ExtractionJob {
  id: string; attachment_id: string; source_id: string; kind: 'pdf' | 'image'; content_hash: string; size_bytes: number;
  extractor_version: string; state: string; source_revision: number; policy_revision: number; processing_revision: number;
  device_id: string | null; lease_token: string | null; lease_until: number | null; generation_id: string | null;
  pdf_text: number; latin_ocr: number; next_batch: number; next_page: number; next_part: number; next_offset: number;
  text_bytes: number; window_start_page: number;
}
interface Attachment { id: string; name: string; declared_type: string | null; sha256: string; object_key: string; size_bytes: number; subject: string }
interface BatchRow { ordinal: number; batch_hash: string; object_key: string; receipt_json: string }
const stale = () => new HttpError(409, 'stale_job', 'This extraction lease or input is no longer current.');
const deleted = () => new HttpError(410, 'deleted', 'This source was deleted.');
const checkpoint = (row: ExtractionJob) => ({ nextBatch: row.next_batch, nextPage: row.next_page, nextPart: row.next_part, nextOffset: row.next_offset });

/** Server parsers enqueue this only after signature detection, under the original parse/extract job fence. */
export function queueDeviceExtraction(db: D1Database, job: ClaimedJob, source: RegistryRow, attachment: { id: string; sha256: string; size_bytes: number }, kind: 'pdf' | 'image', now: number): D1PreparedStatement {
  return db.prepare(`INSERT OR IGNORE INTO device_extraction_jobs(id, attachment_id, source_id, kind, content_hash, size_bytes, extractor_version, state, source_revision, policy_revision, created_at, updated_at)
    SELECT ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ${POLICY_REVISION_SQL}, ?, ? WHERE ${CLAIM_GUARD}`)
    .bind(crypto.randomUUID(), attachment.id, source.id, kind, attachment.sha256, attachment.size_bytes, DEVICE_EXTRACTOR_VERSION, source.revision, now, now, ...guardParams(job));
}

async function attachmentFor(env: Env, source: RegistryRow, id: string): Promise<Attachment | null> {
  return archiveFor(env, source)?.prepare('SELECT a.id, a.name, a.declared_type, a.sha256, a.object_key, a.size_bytes, s.subject FROM attachments a JOIN sources s ON s.id = a.source_id WHERE a.id = ? AND a.source_id = ?')
    .bind(id, source.id).first<Attachment>() ?? null;
}

/** Upgrade compatibility: materialize at most ten previously deferred attachments without re-reading full files. */
async function discoverDeferred(env: Env, now: number): Promise<void> {
  const rows = await env.CONTROL_DB.prepare(`SELECT ap.attachment_id, ap.source_id FROM attachment_processing ap JOIN source_registry r ON r.id = ap.source_id
    WHERE ap.state = 'deferred' AND ap.detail = 'device_unavailable' AND r.state = 'ready' AND r.admission = 'normal'
    AND NOT EXISTS (SELECT 1 FROM device_extraction_jobs j WHERE j.attachment_id = ap.attachment_id) LIMIT 10`).all<{ attachment_id: string; source_id: string }>();
  for (const row of rows.results) {
    const source = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
    if (!source) continue;
    const attachment = await attachmentFor(env, source, row.attachment_id);
    if (!attachment || attachment.size_bytes > MAX_EXTRACTION_TEXT_BYTES) continue;
    const header = await env.ARCHIVE_BUCKET.get(attachment.object_key, { range: { offset: 0, length: 1024 } });
    if (!header) continue;
    const kind = detectKind(attachment.name, attachment.declared_type, new Uint8Array(await header.arrayBuffer()));
    if (kind !== 'pdf' && kind !== 'image') continue;
    await env.CONTROL_DB.prepare(`INSERT OR IGNORE INTO device_extraction_jobs(id, attachment_id, source_id, kind, content_hash, size_bytes, extractor_version, state, source_revision, policy_revision, created_at, updated_at)
      SELECT ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ${POLICY_REVISION_SQL}, ?, ? WHERE EXISTS (SELECT 1 FROM source_registry WHERE id = ? AND state = 'ready' AND admission = 'normal')`)
      .bind(crypto.randomUUID(), attachment.id, source.id, kind, attachment.sha256, attachment.size_bytes, DEVICE_EXTRACTOR_VERSION, source.revision, now, now, source.id).run();
  }
}

/** Used atomically by both inference and extraction claims; one live local task per device. */
export const EXTRACTION_DEVICE_BUSY_SQL = "EXISTS (SELECT 1 FROM device_extraction_jobs WHERE device_id = ? AND state = 'leased' AND lease_until >= ?)";
const inferenceBusy = "EXISTS (SELECT 1 FROM inference_attempts WHERE device_id = ? AND state = 'leased' AND lease_until >= ?)";
const deviceActive = 'EXISTS (SELECT 1 FROM devices WHERE id = ? AND revoked_at IS NULL)';

export async function claimExtraction(env: Env, now: number, body: ReturnType<typeof extractionClaimBody>, auth: AuthContext) {
  if (!body.charging || body.batteryPercent < 30 || ['severe', 'critical', 'emergency', 'shutdown'].includes(body.thermal) || (!body.pdfText && !body.latinOcr)) return { work: null, reason: 'not_ready' };
  const db = env.CONTROL_DB;
  if (await db.prepare(`SELECT 1 AS busy WHERE ${inferenceBusy} OR ${EXTRACTION_DEVICE_BUSY_SQL}`).bind(auth.deviceId, now, auth.deviceId, now).first()) return { work: null, reason: 'busy' };
  await discoverDeferred(env, now);
  const settings = await loadSettings(db);
  const candidates = await db.prepare(`SELECT j.* FROM device_extraction_jobs j JOIN source_registry r ON r.id = j.source_id
    WHERE (j.state = 'queued' OR (j.state = 'leased' AND j.lease_until < ?)) AND r.state = 'ready' AND r.admission = 'normal'
    AND r.privacy != 'restricted_metadata' AND r.privacy_floor != 'restricted_metadata'
    AND ((j.kind = 'pdf' AND ? = 1) OR (? = 1 AND ? = 1 AND r.privacy != 'no_ai' AND r.privacy_floor != 'no_ai'))
    ORDER BY j.created_at, j.id LIMIT 10`).bind(now, body.pdfText ? 1 : 0, body.latinOcr ? 1 : 0, settings.inference.mode !== 'no_ai' ? 1 : 0).all<ExtractionJob>();
  for (const candidate of candidates.results) {
    const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(candidate.source_id).first<RegistryRow>();
    if (!source) continue;
    const noAi = settings.inference.mode === 'no_ai' || source.privacy === 'no_ai' || source.privacy_floor === 'no_ai';
    const ocr = body.latinOcr && !noAi;
    if (candidate.kind === 'image' ? !ocr : !body.pdfText && !ocr) continue;
    const attachment = await attachmentFor(env, source, candidate.attachment_id);
    if (!attachment || attachment.sha256 !== candidate.content_hash || attachment.size_bytes > MAX_EXTRACTION_TEXT_BYTES) continue;
    const changed = candidate.source_revision !== source.revision || candidate.policy_revision !== settings.policyRevision;
    const token = crypto.randomUUID();
    const generation = crypto.randomUUID();
    const eligible = `(state = 'queued' OR (state = 'leased' AND lease_until < ?)) AND NOT ${inferenceBusy} AND NOT ${EXTRACTION_DEVICE_BUSY_SQL} AND ${deviceActive}
      AND EXISTS (SELECT 1 FROM source_registry r WHERE r.id = source_id AND r.revision = ? AND r.state = 'ready' AND r.admission = 'normal' AND r.privacy != 'restricted_metadata' AND r.privacy_floor != 'restricted_metadata') AND ${POLICY_REVISION_SQL} = ?`;
    try {
      await db.batch([
        assertion(db, `EXISTS (SELECT 1 FROM device_extraction_jobs WHERE id = ? AND ${eligible})`, candidate.id, now, auth.deviceId, now, auth.deviceId, now, auth.deviceId, source.revision, settings.policyRevision),
        ...(changed ? [db.prepare('DELETE FROM device_extraction_batches WHERE job_id = ?').bind(candidate.id)] : []),
        db.prepare(`UPDATE attachment_processing SET state = 'extracting', detail = NULL, revision = revision + 1 WHERE attachment_id = ?`).bind(candidate.attachment_id),
        db.prepare(`UPDATE device_extraction_jobs SET state = 'leased', device_id = ?, lease_token = ?, lease_until = ?, generation_id = ?, source_revision = ?, policy_revision = ?,
          processing_revision = (SELECT revision FROM attachment_processing WHERE attachment_id = ?), pdf_text = ?, latin_ocr = ?, updated_at = ?
          ${changed ? ', next_batch = 0, next_page = 0, next_part = 0, next_offset = 0, text_bytes = 0, window_start_page = 0' : ''} WHERE id = ?`)
          .bind(auth.deviceId, token, now + EXTRACTION_LEASE_MS, generation, source.revision, settings.policyRevision, candidate.attachment_id, body.pdfText ? 1 : 0, ocr ? 1 : 0, now, candidate.id),
      ]);
    } catch (error) { if (isAssertionFailure(error)) continue; throw error; }
    const row = (await db.prepare('SELECT * FROM device_extraction_jobs WHERE id = ?').bind(candidate.id).first<ExtractionJob>())!;
    const hashes = await db.prepare('SELECT ordinal, batch_hash AS hash FROM device_extraction_batches WHERE job_id = ? ORDER BY ordinal').bind(row.id).all<{ ordinal: number; hash: string }>();
    return { work: {
      jobId: row.id, attachmentId: row.attachment_id, sourceId: row.source_id, sourceRevision: row.source_revision, policyRevision: row.policy_revision,
      kind: row.kind, contentHash: row.content_hash, sizeBytes: row.size_bytes, extractorVersion: row.extractor_version,
      generationId: row.generation_id, leaseToken: row.lease_token, expiresAt: new Date(row.lease_until!).toISOString(),
      allowOcr: ocr, checkpoint: checkpoint(row), maxPageExclusive: row.window_start_page + 100, acceptedBatches: hashes.results,
    } };
  }
  return { work: null, reason: 'no_work' };
}

/** Authority checked again inside every CONTROL transaction, after any R2/archive writes. */
function liveSql() {
  return `EXISTS (SELECT 1 FROM device_extraction_jobs j JOIN source_registry r ON r.id = j.source_id JOIN attachment_processing ap ON ap.attachment_id = j.attachment_id
    JOIN devices d ON d.id = j.device_id WHERE j.id = ? AND j.lease_token = ? AND j.device_id = ? AND j.state = 'leased' AND j.lease_until >= ?
    AND j.source_revision = r.revision AND j.processing_revision = ap.revision AND ap.state = 'extracting' AND j.policy_revision = ${POLICY_REVISION_SQL}
    AND r.state = 'ready' AND r.admission = 'normal' AND r.privacy != 'restricted_metadata' AND r.privacy_floor != 'restricted_metadata' AND d.revoked_at IS NULL)`;
}
const liveParams = (row: ExtractionJob, auth: AuthContext, now: number) => [row.id, row.lease_token, auth.deviceId, now];

async function live(env: Env, now: number, auth: AuthContext, id: string, token: string, fence?: ExtractionFence): Promise<{ row: ExtractionJob; source: RegistryRow }> {
  const row = await env.CONTROL_DB.prepare('SELECT * FROM device_extraction_jobs WHERE id = ?').bind(id).first<ExtractionJob>();
  if (!row) throw stale();
  const source = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
  if (!source || ['deleting', 'deleted'].includes(source.state)) throw deleted();
  if (row.lease_token !== token || !await env.CONTROL_DB.prepare(`SELECT 1 WHERE ${liveSql()}`).bind(...liveParams(row, auth, now)).first()) throw stale();
  if (fence && (fence.generationId !== row.generation_id || fence.contentHash !== row.content_hash || fence.sourceRevision !== row.source_revision || fence.policyRevision !== row.policy_revision || fence.extractorVersion !== row.extractor_version)) throw stale();
  return { row, source };
}

export async function heartbeatExtraction(env: Env, now: number, body: ReturnType<typeof extractionHeartbeatBody>, auth: AuthContext, id: string) {
  const { row } = await live(env, now, auth, id, body.leaseToken);
  const changed = await env.CONTROL_DB.prepare(`UPDATE device_extraction_jobs SET lease_until = ?, updated_at = ? WHERE id = ? AND ${liveSql()}`)
    .bind(now + EXTRACTION_LEASE_MS, now, id, ...liveParams(row, auth, now)).run();
  if (changed.meta.changes !== 1) throw stale();
  return { jobId: id, expiresAt: new Date(now + EXTRACTION_LEASE_MS).toISOString() };
}

export async function submitExtractionBatch(env: Env, now: number, body: ReturnType<typeof extractionBatchBody>, auth: AuthContext, id: string) {
  const { row, source } = await live(env, now, auth, id, body.leaseToken, body);
  const canonical = canonicalJson({ batchOrdinal: body.batchOrdinal, parts: body.parts });
  const hash = await sha256Hex(canonical);
  const existing = await env.CONTROL_DB.prepare('SELECT batch_hash, receipt_json FROM device_extraction_batches WHERE job_id = ? AND ordinal = ?').bind(id, body.batchOrdinal).first<BatchRow>();
  if (existing) {
    if (existing.batch_hash !== hash) throw new HttpError(409, 'batch_conflict', 'This batch identity already contains different text.');
    return JSON.parse(existing.receipt_json) as Record<string, unknown>;
  }
  if (body.batchOrdinal !== row.next_batch) throw new HttpError(409, 'checkpoint_mismatch', 'Resume from the acknowledged extraction checkpoint.');
  let page = row.next_page, part = row.next_part, offset = row.next_offset, bytes = row.text_bytes;
  for (const item of body.parts) {
    if (item.page !== page || item.part !== part || item.startOffset !== offset || (!item.lastPart && item.text.length === 0)) throw new HttpError(409, 'checkpoint_mismatch', 'Page parts must be contiguous.');
    if (item.page >= row.window_start_page + 100 || (row.kind === 'image' && item.page !== 0)) throw new HttpError(413, 'page_limit', 'Resume the next pages explicitly after this bounded pass.');
    if (item.method === 'ocr' ? !row.latin_ocr : row.kind !== 'pdf' || !row.pdf_text) throw new HttpError(409, 'policy_blocked', 'This extraction method is not permitted for this lease.');
    bytes += new TextEncoder().encode(item.text).byteLength;
    offset += item.text.length;
    if (item.lastPart) { page += 1; part = 0; offset = 0; } else part += 1;
  }
  if (bytes > MAX_EXTRACTION_TEXT_BYTES) throw new HttpError(413, 'text_limit', 'The extracted text exceeds the document limit.');
  const key = `text/${source.id}/device/${row.attachment_id}/${id}/${body.batchOrdinal}/${hash}.json`;
  const receipt = { jobId: id, batchOrdinal: body.batchOrdinal, batchHash: hash, nextBatch: body.batchOrdinal + 1, nextPage: page, nextPart: part, nextOffset: offset };
  // Content-addressed staging is never an active searchable generation, including late stale writes.
  await env.ARCHIVE_BUCKET.put(key, canonical, { httpMetadata: { contentType: 'application/json' } });
  try {
    await env.CONTROL_DB.batch([
      assertion(env.CONTROL_DB, liveSql(), ...liveParams(row, auth, now)),
      assertion(env.CONTROL_DB, 'EXISTS (SELECT 1 FROM device_extraction_jobs WHERE id = ? AND next_batch = ?)', id, body.batchOrdinal),
      env.CONTROL_DB.prepare('INSERT INTO device_extraction_batches(job_id, ordinal, batch_hash, object_key, receipt_json, created_at) VALUES (?, ?, ?, ?, ?, ?)')
        .bind(id, body.batchOrdinal, hash, key, JSON.stringify(receipt), now),
      env.CONTROL_DB.prepare('UPDATE device_extraction_jobs SET next_batch = next_batch + 1, next_page = ?, next_part = ?, next_offset = ?, text_bytes = ?, updated_at = ? WHERE id = ?')
        .bind(page, part, offset, bytes, now, id),
    ]);
  } catch (error) {
    // Deletion may finish while an R2 write is in flight; remove that late object as well.
    const current = await env.CONTROL_DB.prepare('SELECT state FROM source_registry WHERE id = ?').bind(source.id).first<{ state: string }>();
    if (!current || ['deleting', 'deleted'].includes(current.state)) { await env.ARCHIVE_BUCKET.delete(key); throw deleted(); }
    if (isAssertionFailure(error)) {
      const duplicate = await env.CONTROL_DB.prepare('SELECT batch_hash, receipt_json FROM device_extraction_batches WHERE job_id = ? AND ordinal = ?').bind(id, body.batchOrdinal).first<BatchRow>();
      await live(env, now, auth, id, body.leaseToken, body);
      if (duplicate?.batch_hash === hash) return JSON.parse(duplicate.receipt_json) as Record<string, unknown>;
      if (duplicate) throw new HttpError(409, 'batch_conflict', 'This batch identity already contains different text.');
      throw stale();
    }
    throw error;
  }
  return receipt;
}

export async function finishExtraction(env: Env, now: number, body: ReturnType<typeof extractionFinishBody>, auth: AuthContext, id: string) {
  const { row, source } = await live(env, now, auth, id, body.leaseToken, body);
  if (row.next_part !== 0 || row.next_batch < 1) throw new HttpError(409, 'checkpoint_mismatch', 'Finish only after a complete acknowledged page.');
  const batches = (await env.CONTROL_DB.prepare('SELECT ordinal, batch_hash, object_key, receipt_json FROM device_extraction_batches WHERE job_id = ? ORDER BY ordinal LIMIT ?').bind(id, MAX_EXTRACTION_BATCHES + 1).all<BatchRow>()).results;
  const manifestHash = await sha256Hex(canonicalJson(batches.map((item) => ({ ordinal: item.ordinal, hash: item.batch_hash }))));
  if (batches.length !== row.next_batch || manifestHash !== body.manifestHash) throw new HttpError(409, 'manifest_mismatch', 'The final manifest must contain every acknowledged batch in order.');
  const archive = archiveFor(env, source);
  const attachment = await attachmentFor(env, source, row.attachment_id);
  if (!archive || !attachment || attachment.sha256 !== body.contentHash) throw stale();
  const generation = `device:${row.generation_id}:${manifestHash}`;
  let ordinal = 0;
  for (const batch of batches) {
    const object = await env.ARCHIVE_BUCKET.get(batch.object_key);
    if (!object || object.size > 256 * 1024) throw new HttpError(503, 'batch_unavailable', 'An acknowledged extraction batch is unavailable.', {}, true);
    const stored = await object.text();
    if (await sha256Hex(stored) !== batch.batch_hash) throw new HttpError(503, 'batch_hash_mismatch', 'An acknowledged extraction batch failed verification.', {}, true);
    const parsed = JSON.parse(stored) as { batchOrdinal: number; parts: ExtractionPart[] };
    if (parsed.batchOrdinal !== batch.ordinal) throw stale();
    const writes: D1PreparedStatement[] = [];
    for (const item of parsed.parts) {
      const chunkId = `${row.attachment_id}:${generation}:${ordinal}`;
      writes.push(archive.prepare('INSERT OR IGNORE INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
        .bind(chunkId, row.source_id, row.attachment_id, generation, ordinal++, item.page, item.startOffset, item.text, await sha256Hex(item.text), item.method));
      writes.push(archive.prepare('INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) SELECT ?, ?, ?, ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM source_fts WHERE chunk_id = ?)')
        .bind(row.source_id, chunkId, generation, attachment.subject, item.text, attachment.name, chunkId));
    }
    for (let at = 0; at < writes.length; at += 50) await archive.batch(writes.slice(at, at + 50));
  }
  const state = body.complete ? 'ready' : 'deferred';
  const detail = body.complete ? null : 'page_limit';
  const projection = JSON.stringify({ id: row.attachment_id, sourceId: row.source_id, state, detail, revision: row.processing_revision + 1 });
  try {
    await env.CONTROL_DB.batch([
      assertion(env.CONTROL_DB, liveSql(), ...liveParams(row, auth, now)),
      assertion(env.CONTROL_DB, 'EXISTS (SELECT 1 FROM device_extraction_jobs WHERE id = ? AND next_batch = ?)', id, row.next_batch),
      env.CONTROL_DB.prepare('UPDATE attachment_processing SET state = ?, detail = ?, active_generation = ?, revision = revision + 1, policy_revision = ? WHERE attachment_id = ?')
        .bind(state, detail, generation, row.policy_revision, row.attachment_id),
      env.CONTROL_DB.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('attachment', ?, ?, 0, ?, ?)").bind(row.attachment_id, row.processing_revision + 1, projection, now),
      env.CONTROL_DB.prepare('UPDATE device_extraction_jobs SET state = ?, lease_until = NULL, processing_revision = processing_revision + 1, updated_at = ? WHERE id = ?').bind(body.complete ? 'complete' : 'deferred', now, id),
    ]);
  } catch (error) { if (isAssertionFailure(error)) throw stale(); throw error; }
  return { jobId: id, attachmentId: row.attachment_id, state, detail, revision: row.processing_revision + 1, ...checkpoint(row), manifestHash };
}

/** Unavailable OCR, encrypted files and stopped device work remain explicit; no empty success is invented. */
export async function deferExtraction(env: Env, now: number, body: ReturnType<typeof extractionDeferBody>, auth: AuthContext, id: string) {
  const { row } = await live(env, now, auth, id, body.leaseToken, body);
  const projection = JSON.stringify({ id: row.attachment_id, sourceId: row.source_id, state: 'deferred', detail: body.reason, revision: row.processing_revision + 1 });
  try {
    await env.CONTROL_DB.batch([
      assertion(env.CONTROL_DB, liveSql(), ...liveParams(row, auth, now)),
      env.CONTROL_DB.prepare("UPDATE attachment_processing SET state = 'deferred', detail = ?, revision = revision + 1 WHERE attachment_id = ?").bind(body.reason, row.attachment_id),
      env.CONTROL_DB.prepare("UPDATE device_extraction_jobs SET state = 'deferred', lease_until = NULL, processing_revision = processing_revision + 1, updated_at = ? WHERE id = ?").bind(now, id),
      env.CONTROL_DB.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('attachment', ?, ?, 0, ?, ?)").bind(row.attachment_id, row.processing_revision + 1, projection, now),
    ]);
  } catch (error) { if (isAssertionFailure(error)) throw stale(); throw error; }
  return { jobId: id, state: 'deferred', detail: body.reason, revision: row.processing_revision + 1, ...checkpoint(row) };
}

/** Explicit owner request for another <=100-page pass; no automatic lease reclamation bypasses this gate. */
export async function resumeExtraction(env: Env, now: number, body: ReturnType<typeof extractionResumeBody>, auth: AuthContext, id: string) {
  const row = await env.CONTROL_DB.prepare('SELECT j.* FROM device_extraction_jobs j JOIN attachment_processing ap ON ap.attachment_id = j.attachment_id JOIN source_registry r ON r.id = j.source_id WHERE j.id = ? AND j.state = ? AND ap.revision = ? AND r.state = ? AND r.admission = ?')
    .bind(id, 'deferred', body.expectedRevision, 'ready', 'normal').first<ExtractionJob>();
  if (!row) throw stale();
  const changed = await env.CONTROL_DB.prepare(`UPDATE device_extraction_jobs SET state = 'queued', window_start_page = next_page, updated_at = ? WHERE id = ? AND state = 'deferred' AND ${deviceActive}
    AND EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND revision = ?)`)
    .bind(now, id, auth.deviceId, row.attachment_id, body.expectedRevision).run();
  if (changed.meta.changes !== 1) throw stale();
  return { jobId: id, state: 'queued', ...checkpoint(row) };
}
