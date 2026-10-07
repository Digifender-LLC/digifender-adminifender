import { chunkText } from '../archive/normalize';
import type { Env } from '../env';
import { sha256Hex } from '../ids';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { docxText } from './docx';
import { queueDeviceExtraction } from './device';
import { detectKind, plainText } from './text';
import { ExtractionError, MAX_EXPANDED_BYTES } from './zip';

export const EXTRACTOR_VERSION = 'server-extract-v1';

/** Generation IDs are per attachment and extractor, so retries reuse chunk IDs and other attachments are untouched. */
export const generationFor = (attachmentId: string) => `att:${attachmentId}:${EXTRACTOR_VERSION}`;

const SQL_UUID_V4 =
  "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))";
const DEDUPE = `'extract:' || ap.attachment_id || ':' || ap.revision || ':${EXTRACTOR_VERSION}'`;

/**
 * Queues server extraction for every stored attachment of a normal, ready source in the caller's batch
 * (lld.md#text-extraction-and-ocr). Keyed by attachment, processing revision and extractor version.
 */
export function extractionJobStatements(db: D1Database, sourceId: string, now: number, condition: string, params: unknown[]): D1PreparedStatement[] {
  const eligible = `FROM attachment_processing ap JOIN source_registry r ON r.id = ap.source_id
    WHERE ap.source_id = ? AND ap.state = 'stored' AND r.state = 'ready' AND r.admission = 'normal' AND ${condition}`;
  return [
    db.prepare(
      `INSERT OR IGNORE INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at)
       SELECT ${SQL_UUID_V4}, ${DEDUPE}, 'extract', ap.source_id, ap.revision, 'pending', ?, json_object('attachmentId', ap.attachment_id, 'policyRevision', ap.policy_revision), ? ${eligible}`,
    ).bind(now, now, sourceId, ...params),
    db.prepare(
      `INSERT OR IGNORE INTO outbox(id, dedupe_key, kind, reference_id, state, next_at)
       SELECT ${SQL_UUID_V4}, 'job:' || j.id, 'job', j.id, 'pending', ? FROM jobs j JOIN attachment_processing ap ON j.dedupe_key = ${DEDUPE}
       WHERE ap.source_id = ? AND ap.state = 'stored'`,
    ).bind(now, sourceId),
    db.prepare(
      `UPDATE attachment_processing AS ap SET state = 'queued', revision = ap.revision + 1, job_id = (SELECT j.id FROM jobs j WHERE j.dedupe_key = ${DEDUPE})
       WHERE ap.source_id = ? AND ap.state = 'stored' AND EXISTS (SELECT 1 FROM jobs j WHERE j.dedupe_key = ${DEDUPE})`,
    ).bind(sourceId),
  ];
}

interface ProcessingRow {
  attachment_id: string;
  source_id: string;
  revision: number;
  state: string;
  policy_revision: number;
  job_id: string | null;
}

type Outcome = { state: 'ready'; generation: string } | { state: 'unsupported' | 'failed' | 'deferred'; detail: string };

/** Fenced publication of the attachment state and its change record; only the job that owns the row may write. */
function publish(env: Env, job: ClaimedJob, row: ProcessingRow, outcome: Outcome, now: number): D1PreparedStatement[] {
  const db = env.CONTROL_DB;
  const [guardId, guardToken] = guardParams(job);
  const generation = outcome.state === 'ready' ? outcome.generation : null;
  const detail = outcome.state === 'ready' ? null : outcome.detail;
  const projection = JSON.stringify({ id: row.attachment_id, sourceId: row.source_id, state: outcome.state, detail, revision: row.revision + 1 });
  return [
    db.prepare(
      `UPDATE attachment_processing SET state = ?, detail = ?, active_generation = COALESCE(?, active_generation), revision = revision + 1
       WHERE attachment_id = ? AND job_id = ? AND revision = ? AND policy_revision = ? AND ${CLAIM_GUARD}`,
    ).bind(outcome.state, detail, generation, row.attachment_id, job.id, row.revision, row.policy_revision, guardId, guardToken),
    db.prepare(
      `INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at)
       SELECT 'attachment', attachment_id, revision, 0, ?, ? FROM attachment_processing WHERE attachment_id = ? AND job_id = ? AND revision = ? AND ${CLAIM_GUARD}`,
    ).bind(projection, now, row.attachment_id, job.id, row.revision + 1, guardId, guardToken),
  ];
}

/**
 * Server extraction for text, CSV, JSON, Markdown, HTML and DOCX. PDFs and images wait for device work; other types
 * stay downloadable as unsupported. Chunks are written to the archive first and become readable only when the
 * fenced control update publishes the generation.
 */
export async function extractJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  const input = job.input as { attachmentId?: unknown; policyRevision?: unknown };
  if (typeof input.attachmentId !== 'string') return { kind: 'fail', errorCode: 'invalid_input' };
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT attachment_id, source_id, revision, state, policy_revision, job_id FROM attachment_processing WHERE attachment_id = ?').bind(input.attachmentId).first<ProcessingRow>();
  if (!row || row.job_id !== job.id || !['queued', 'extracting'].includes(row.state)) return { kind: 'complete', output: { skipped: 'superseded' } };
  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
  // Quarantine never starts extraction.
  if (!source || source.state !== 'ready' || source.admission !== 'normal') return { kind: 'complete', output: { skipped: 'not_eligible' } };
  await db.prepare("UPDATE attachment_processing SET state = 'extracting' WHERE attachment_id = ? AND job_id = ? AND state = 'queued'").bind(row.attachment_id, job.id).run();

  const archive = archiveFor(env, source);
  const attachment = archive
    ? await archive.prepare('SELECT a.id, a.name, a.declared_type, a.sha256, a.object_key, a.size_bytes, s.subject FROM attachments a JOIN sources s ON s.id = a.source_id WHERE a.id = ?').bind(row.attachment_id).first<{
        id: string;
        name: string;
        declared_type: string | null;
        sha256: string;
        object_key: string;
        size_bytes: number;
        subject: string;
      }>()
    : null;
  if (!archive || !attachment) return { kind: 'retry', errorCode: 'archive_unavailable' };
  const object = await env.ARCHIVE_BUCKET.get(attachment.object_key);
  if (!object) return { kind: 'retry', errorCode: 'attachment_unavailable' };
  if (object.size > MAX_EXPANDED_BYTES) return { kind: 'complete', output: { reason: 'file_limit' }, statements: publish(env, job, row, { state: 'unsupported', detail: 'limit' }, now) };
  const bytes = new Uint8Array(await object.arrayBuffer());
  if ((await sha256Hex(bytes)) !== attachment.sha256) return { kind: 'fail', errorCode: 'attachment_hash_mismatch', statements: publish(env, job, row, { state: 'failed', detail: 'parser_error' }, now) };

  const kind = detectKind(attachment.name, attachment.declared_type, bytes);
  if (kind === 'unsupported') return { kind: 'complete', output: { kind }, statements: publish(env, job, row, { state: 'unsupported', detail: 'unsupported_type' }, now) };
  if (kind === 'image' && source.privacy === 'no_ai') return { kind: 'complete', output: { kind }, statements: publish(env, job, row, { state: 'unsupported', detail: 'policy_blocked' }, now) };
  if (kind === 'pdf' || kind === 'image') return { kind: 'complete', output: { kind }, statements: [
    ...publish(env, job, row, { state: 'deferred', detail: 'device_unavailable' }, now),
    queueDeviceExtraction(db, job, source, attachment, kind, now),
  ] };

  let text: string;
  try {
    text = kind === 'docx' ? await docxText(bytes) : plainText(kind, bytes);
  } catch (error) {
    if (!(error instanceof ExtractionError)) throw error;
    // Deterministic for these bytes, so no retry; the original stays downloadable.
    return { kind: 'complete', output: { kind, reason: error.reason }, statements: publish(env, job, row, { state: 'failed', detail: error.detail }, now) };
  }
  text = text.replace(/[ \t]+$/gm, '').replace(/\n{3,}/g, '\n\n').trim();
  if (new TextEncoder().encode(text).byteLength > MAX_EXPANDED_BYTES) return { kind: 'complete', output: { kind }, statements: publish(env, job, row, { state: 'failed', detail: 'limit' }, now) };

  const generation = generationFor(attachment.id);
  const chunks = chunkText(text);
  const chunkId = (ordinal: number) => `${attachment.id}:${generation}:${ordinal}`;
  const writes: D1PreparedStatement[] = [
    ...(await Promise.all(
      chunks.map(async (chunk) =>
        archive.prepare('INSERT OR IGNORE INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)')
          .bind(chunkId(chunk.ordinal), source.id, attachment.id, generation, chunk.ordinal, chunk.startOffset, chunk.text, await sha256Hex(chunk.text), kind),
      ),
    )),
    archive.prepare('DELETE FROM source_fts WHERE source_id = ? AND generation_id = ?').bind(source.id, generation),
    ...chunks.map((chunk) =>
      archive.prepare('INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES (?, ?, ?, ?, ?, ?)')
        .bind(source.id, chunkId(chunk.ordinal), generation, attachment.subject, chunk.text, attachment.name),
    ),
  ];
  // D1 limits statements per batch; large documents are written in slices, all idempotent.
  for (let i = 0; i < writes.length; i += 50) await archive.batch(writes.slice(i, i + 50));
  return { kind: 'complete', output: { kind, chunks: chunks.length }, statements: publish(env, job, row, { state: 'ready', generation }, now) };
}
