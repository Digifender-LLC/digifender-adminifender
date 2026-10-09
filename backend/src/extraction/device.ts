import type { AuthContext } from '../auth/sessions';
import { chunkText } from '../archive/normalize';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { sha256Hex } from '../ids';
import { POLICY_REVISION_SQL, loadSettings } from '../inference/settings';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';

/**
 * Device extraction of PDFs and images (lld.md#text-extraction-and-ocr). The phone leases one attachment, downloads its
 * bytes through the ordinary attachment endpoint, and submits text in immutable, numbered batches. Batches become archive
 * chunks of an unpublished generation; only the final batch, in one fenced control transaction that rechecks the lease,
 * the attachment, the source and the policy, points the attachment at that generation. Nothing is readable before that.
 */
export const EXTRACTION_LEASE_MS = 10 * 60 * 1000;
/** Pages one lease may read. A longer document is read over several leases, each started by the owner's "read more". */
export const MAX_DEVICE_PAGES = 100;
/** Pages of one document that can be read in total. */
export const MAX_DOCUMENT_PAGES = 1000;
export const MAX_BATCH_PAGES = 10;
export const MAX_BATCH_TEXT_BYTES = 128 * 1024;
/** Chunk ordinals are `batchOrdinal * STRIDE + n`, so a retried batch always rewrites the same rows. */
const ORDINAL_STRIDE = 100_000;
/** Unpublished generations are removed after a day (lld.md#text-extraction-and-ocr). */
export const UNPUBLISHED_GENERATION_MS = 24 * 60 * 60 * 1000;
const CLOSED_LEASE_KEEP_MS = 30 * 24 * 60 * 60 * 1000;

const HEX64 = /^[0-9a-f]{64}$/;
const encoder = new TextEncoder();
const leaseToken = v.uuid();
const extractorVersion = v.string({ min: 3, max: 64, pattern: /^[a-z0-9][a-z0-9._-]*$/ });

export const claimBody = v.object({ extractorVersion, pdfText: v.boolean(), ocr: v.boolean() });
export const heartbeatBody = v.object({ leaseToken });

const pageEntry = v.object({
  page: v.integer({ min: 1, max: MAX_DOCUMENT_PAGES }),
  part: v.integer({ min: 0, max: 999 }),
  method: v.oneOf(['pdf_text', 'ocr'] as const),
  text: v.string({ min: 1, max: MAX_BATCH_TEXT_BYTES }),
  /** Recognizer evidence, not a probability. It is validated and covered by the batch hash but not stored: nothing shows it. */
  confidence: v.optional(v.number({ min: 0, max: 1 })),
});

const batchShape = v.object({
  leaseToken,
  contentHash: v.string({ min: 64, max: 64, pattern: HEX64 }),
  extractorVersion,
  policyRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }),
  generationId: v.string({ max: 200 }),
  batchOrdinal: v.integer({ min: 0, max: MAX_DOCUMENT_PAGES }),
  pages: v.array(pageEntry, { max: 200 }),
  lastPage: v.integer({ min: 1, max: MAX_DOCUMENT_PAGES }),
  totalPages: v.optional(v.integer({ min: 1, max: 100_000 })),
  complete: v.boolean(),
  manifestHash: v.optional(v.string({ min: 64, max: 64, pattern: HEX64 })),
});

/** The shape plus the rules between fields that the shared schema states with if/then/else. */
export const batchBody: Validator<ReturnType<typeof batchShape>> = (value, path) => {
  const body = batchShape(value, path);
  if (body.complete !== (body.manifestHash !== undefined)) throw invalid(`${path}.manifestHash`, body.complete ? 'required' : 'only_when_complete');
  if (!body.complete && body.totalPages !== undefined) throw invalid(`${path}.totalPages`, 'only_when_complete');
  return body;
};

export const OUTCOMES = ['encrypted', 'unsupported', 'failed', 'unavailable'] as const;
export const outcomeBody = v.object({ leaseToken, outcome: v.oneOf(OUTCOMES) });

const leaseLost = () => new HttpError(409, 'lease_lost', 'This file is no longer assigned to this device.');
const invalid = (path: string, reason: string) => new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason });

export const deviceGeneration = (attachmentId: string, contentHash: string, version: string, policyRevision: number) =>
  `dev:${attachmentId}:${contentHash.slice(0, 16)}:${version}:p${policyRevision}`;

interface LeaseRow {
  id: string;
  attachment_id: string;
  source_id: string;
  device_id: string;
  lease_token: string;
  lease_until: number;
  state: string;
  processing_revision: number;
  source_revision: number;
  policy_revision: number;
  content_hash: string;
  extractor_version: string;
  generation_id: string;
  ocr_allowed: number;
  start_page: number;
  base_generation_id: string | null;
}

const LEASE_COLUMNS =
  'id, attachment_id, source_id, device_id, lease_token, lease_until, state, processing_revision, source_revision, policy_revision, content_hash, extractor_version, generation_id, ocr_allowed, start_page, base_generation_id';

const loadLease = (db: D1Database, id: string) => db.prepare(`SELECT ${LEASE_COLUMNS} FROM extraction_leases WHERE id = ?`).bind(id).first<LeaseRow>();
const loadSource = (db: D1Database, id: string) => db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(id).first<RegistryRow>();

/** OCR is generative-adjacent processing: it needs the global mode and the email to allow AI (`no_ai` allows deterministic text only). */
const ocrAllowedFor = (mode: string, source: RegistryRow) => mode !== 'no_ai' && source.privacy !== 'no_ai';

/** Same projection the server extraction publishes, built from the row so it always matches the stored state. */
const attachmentChange = (db: D1Database, attachmentId: string, revision: number, now: number) =>
  db
    .prepare(
      `INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at)
       SELECT 'attachment', attachment_id, revision, 0, json_object('id', attachment_id, 'sourceId', source_id, 'state', state, 'detail', detail, 'revision', revision), ?
       FROM attachment_processing WHERE attachment_id = ? AND revision = ?`,
    )
    .bind(now, attachmentId, revision);

const NO_LIVE_LEASE = "NOT EXISTS (SELECT 1 FROM extraction_leases l WHERE l.attachment_id = attachment_processing.attachment_id AND l.state = 'leased')";
/** The attachment's current revision was written by a device claim. Server extraction never produces such a revision. */
const HELD_BY_DEVICE = "EXISTS (SELECT 1 FROM extraction_leases d WHERE d.attachment_id = attachment_processing.attachment_id AND d.processing_revision = attachment_processing.revision)";

/**
 * Attachments left `extracting` with no live device lease (it expired, was cancelled or closed) go back to
 * `deferred: device_unavailable` so another claim can take them. Server extraction never writes a revision a lease holds, so it is not touched here.
 */
export async function revertOrphans(db: D1Database, now: number, limit = 50): Promise<number> {
  const orphans = (
    await db
      .prepare(`SELECT attachment_id, revision FROM attachment_processing WHERE state = 'extracting' AND ${HELD_BY_DEVICE} AND ${NO_LIVE_LEASE} LIMIT ?`)
      .bind(limit)
      .all<{ attachment_id: string; revision: number }>()
  ).results;
  let reverted = 0;
  for (const orphan of orphans) {
    const results = await db.batch([
      db
        .prepare(
          `UPDATE attachment_processing SET state = 'deferred', detail = 'device_unavailable', revision = revision + 1
           WHERE attachment_id = ? AND revision = ? AND state = 'extracting' AND ${HELD_BY_DEVICE} AND ${NO_LIVE_LEASE}`,
        )
        .bind(orphan.attachment_id, orphan.revision),
      attachmentChange(db, orphan.attachment_id, orphan.revision + 1, now),
    ]);
    reverted += results[0]?.meta.changes ?? 0;
  }
  return reverted;
}

/** Leases whose time ran out are closed, and the files they held return to the queue. */
export async function expireLeases(db: D1Database, now: number): Promise<void> {
  await db.prepare("UPDATE extraction_leases SET state = 'expired', closed_at = ? WHERE state = 'leased' AND lease_until < ?").bind(now, now).run();
  await revertOrphans(db, now);
}

async function closeLease(db: D1Database, id: string, state: 'cancelled' | 'released', now: number): Promise<void> {
  await db.prepare("UPDATE extraction_leases SET state = ?, closed_at = ? WHERE id = ? AND state = 'leased'").bind(state, now, id).run();
  await revertOrphans(db, now);
}

/**
 * Where a generation stands. The hashes are the server's own batch hashes in order: the phone keeps them and sends their
 * digest as `manifestHash` with the final batch (the batches before it, in order), so it never has to reproduce how the server hashes text.
 */
async function checkpointFor(db: D1Database, generationId: string): Promise<{ nextBatchOrdinal: number; nextPage: number; batchHashes: string[] }> {
  const rows = (await db.prepare('SELECT batch_hash, last_page FROM extraction_batches WHERE generation_id = ? ORDER BY ordinal').bind(generationId).all<{ batch_hash: string; last_page: number }>()).results;
  return { nextBatchOrdinal: rows.length, nextPage: (rows.at(-1)?.last_page ?? 0) + 1, batchHashes: rows.map((r) => r.batch_hash) };
}

/**
 * Makes the generation a resumed read continues in: the published generation's chunks, search rows and batch records,
 * copied under the new generation. Done before the lease exists, and only while the new generation has no batches yet, so a
 * lease never sees a half-made copy; an interrupted copy is simply made again. Returns false when there is nothing to copy.
 */
async function prepareResumedGeneration(db: D1Database, archive: D1Database, sourceId: string, attachmentId: string, baseGeneration: string, newGeneration: string, now: number): Promise<boolean> {
  const existing = await db.prepare('SELECT COUNT(*) AS n FROM extraction_batches WHERE generation_id = ?').bind(newGeneration).first<{ n: number }>();
  if ((existing?.n ?? 0) > 0) return true;
  const base = await db.prepare('SELECT COUNT(*) AS n FROM extraction_batches WHERE generation_id = ?').bind(baseGeneration).first<{ n: number }>();
  if (!base || base.n === 0) return false;
  await archive.batch([
    archive.prepare('DELETE FROM source_fts WHERE source_id = ? AND generation_id = ?').bind(sourceId, newGeneration),
    archive.prepare('DELETE FROM chunks WHERE source_id = ? AND attachment_id = ? AND generation_id = ?').bind(sourceId, attachmentId, newGeneration),
    archive
      .prepare(
        `INSERT INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method)
         SELECT attachment_id || ':' || ?1 || ':' || ordinal, source_id, attachment_id, ?1, ordinal, page, start_offset, text, text_hash, method
         FROM chunks WHERE source_id = ?2 AND attachment_id = ?3 AND generation_id = ?4`,
      )
      .bind(newGeneration, sourceId, attachmentId, baseGeneration),
    archive
      .prepare(
        `INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename)
         SELECT f.source_id, n.id, ?1, f.subject, f.body, f.filename
         FROM source_fts f JOIN chunks o ON o.id = f.chunk_id JOIN chunks n ON n.generation_id = ?1 AND n.attachment_id = o.attachment_id AND n.ordinal = o.ordinal
         WHERE f.source_id = ?2 AND f.generation_id = ?3`,
      )
      .bind(newGeneration, sourceId, baseGeneration),
  ]);
  // The batch records go last: their presence is what says the copy is complete.
  await db
    .prepare(
      `INSERT INTO extraction_batches(generation_id, ordinal, attachment_id, source_id, batch_hash, chunks_digest, chunk_count, last_page, created_at)
       SELECT ?, ordinal, attachment_id, source_id, batch_hash, chunks_digest, chunk_count, last_page, ? FROM extraction_batches WHERE generation_id = ?`,
    )
    .bind(newGeneration, now, baseGeneration)
    .run();
  return true;
}

// POST /device-work/extraction/claim — leases one deferred PDF or image the phone can process now.
export async function claimExtraction(env: Env, now: number, body: ReturnType<typeof claimBody>, auth: AuthContext) {
  const db = env.CONTROL_DB;
  await expireLeases(db, now);
  if (!body.pdfText && !body.ocr) return { work: null, reason: 'no_capability' };
  const busy = await db.prepare("SELECT 1 AS hit FROM extraction_leases WHERE device_id = ? AND state = 'leased'").bind(auth.deviceId).first();
  if (busy) return { work: null, reason: 'busy' };
  const settings = await loadSettings(db);

  const candidates = (
    await db
      .prepare(
        `SELECT ap.attachment_id, ap.source_id, ap.revision, ap.device_kind, ap.resume_page, ap.active_generation FROM attachment_processing ap JOIN source_registry r ON r.id = ap.source_id
         WHERE ap.state = 'deferred' AND ap.detail = 'device_unavailable' AND ap.device_kind IS NOT NULL AND r.state = 'ready' AND r.admission = 'normal'
         ORDER BY ap.rowid LIMIT 50`,
      )
      .all<{ attachment_id: string; source_id: string; revision: number; device_kind: 'pdf' | 'image'; resume_page: number | null; active_generation: string | null }>()
  ).results;

  for (const candidate of candidates) {
    const source = await loadSource(db, candidate.source_id);
    if (!source || source.state !== 'ready' || source.admission !== 'normal') continue;
    const ocrAllowed = ocrAllowedFor(settings.inference.mode, source);
    const canRun = candidate.device_kind === 'image' ? body.ocr && ocrAllowed : body.pdfText || (body.ocr && ocrAllowed);
    if (!canRun) continue;
    const archive = archiveFor(env, source);
    const stored = archive ? await archive.prepare('SELECT sha256, size_bytes FROM attachments WHERE id = ? AND source_id = ?').bind(candidate.attachment_id, source.id).first<{ sha256: string; size_bytes: number }>() : null;
    if (!stored) continue;

    const leaseId = crypto.randomUUID();
    const token = crypto.randomUUID();
    // A resumed read starts a new generation beside the published one: it begins as a copy of it and continues from the next page.
    const resuming = candidate.resume_page !== null && candidate.active_generation !== null;
    const startPage = resuming ? candidate.resume_page! : 1;
    const generationId = deviceGeneration(candidate.attachment_id, stored.sha256, body.extractorVersion, settings.policyRevision) + (resuming ? `:r${startPage}` : '');
    if (resuming && !(await prepareResumedGeneration(db, archive!, source.id, candidate.attachment_id, candidate.active_generation!, generationId, now))) continue;
    try {
      await db.batch([
        assertion(db, "EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND state = 'deferred' AND detail = 'device_unavailable' AND revision = ?)", candidate.attachment_id, candidate.revision),
        assertion(db, "EXISTS (SELECT 1 FROM source_registry WHERE id = ? AND state = 'ready' AND admission = 'normal' AND revision = ?)", source.id, source.revision),
        assertion(db, `${POLICY_REVISION_SQL} = ?`, settings.policyRevision),
        db
          .prepare(
            `INSERT INTO extraction_leases(id, attachment_id, source_id, device_id, lease_token, lease_until, state, processing_revision, source_revision, policy_revision,
               content_hash, extractor_version, generation_id, ocr_allowed, created_at, start_page, base_generation_id)
             VALUES (?, ?, ?, ?, ?, ?, 'leased', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          )
          .bind(leaseId, candidate.attachment_id, source.id, auth.deviceId, token, now + EXTRACTION_LEASE_MS, candidate.revision + 1, source.revision, settings.policyRevision, stored.sha256, body.extractorVersion, generationId, ocrAllowed ? 1 : 0, now, startPage, resuming ? candidate.active_generation : null),
        db.prepare("UPDATE attachment_processing SET state = 'extracting', detail = NULL, revision = revision + 1 WHERE attachment_id = ? AND revision = ?").bind(candidate.attachment_id, candidate.revision),
        attachmentChange(db, candidate.attachment_id, candidate.revision + 1, now),
      ]);
    } catch (error) {
      // Someone else took the file, or the attachment or source changed between the read and the write: try the next one.
      if (isAssertionFailure(error) || (error instanceof Error && /UNIQUE constraint failed/.test(error.message))) continue;
      throw error;
    }
    const checkpoint = await checkpointFor(db, generationId);
    return {
      work: {
        leaseId,
        leaseToken: token,
        attachmentId: candidate.attachment_id,
        sourceId: source.id,
        kind: candidate.device_kind,
        contentHash: stored.sha256,
        sizeBytes: stored.size_bytes,
        extractorVersion: body.extractorVersion,
        policyRevision: settings.policyRevision,
        generationId,
        ocrAllowed,
        startPage,
        maxPages: MAX_DEVICE_PAGES,
        maxBatchPages: MAX_BATCH_PAGES,
        maxBatchBytes: MAX_BATCH_TEXT_BYTES,
        nextBatchOrdinal: checkpoint.nextBatchOrdinal,
        nextPage: checkpoint.nextPage,
        batchHashes: checkpoint.batchHashes,
        expiresAt: new Date(now + EXTRACTION_LEASE_MS).toISOString(),
      },
    };
  }
  return { work: null, reason: 'no_work' };
}

// POST /device-work/extraction/{leaseId}/heartbeat
export async function extractionHeartbeat(env: Env, now: number, body: ReturnType<typeof heartbeatBody>, auth: AuthContext, leaseId: string) {
  const result = await env.CONTROL_DB.prepare("UPDATE extraction_leases SET lease_until = ? WHERE id = ? AND lease_token = ? AND device_id = ? AND state = 'leased' AND lease_until >= ?")
    .bind(now + EXTRACTION_LEASE_MS, leaseId, body.leaseToken, auth.deviceId, now)
    .run();
  if (result.meta.changes !== 1) throw leaseLost();
  return { leaseId, expiresAt: new Date(now + EXTRACTION_LEASE_MS).toISOString() };
}

type Entry = { page: number; part: number; method: 'pdf_text' | 'ocr'; text: string; confidence?: number | undefined };

const LONE_SURROGATE = /[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/g;
const normalizeText = (text: string) =>
  text
    .replace(/\r\n?/g, '\n')
    .replace(LONE_SURROGATE, '�')
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '')
    .replace(/[ \t]+$/gm, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();

/** Order and shape rules that depend on where the generation stands. */
/** [lastAllowedPage] is the last page this lease may read: its first page plus the per-lease budget. */
function checkStructure(body: ReturnType<typeof batchBody>, nextPage: number, lastAllowedPage: number) {
  if (body.lastPage < nextPage) throw invalid('$.lastPage', 'must_advance');
  if (body.lastPage - nextPage + 1 > MAX_BATCH_PAGES) throw invalid('$.lastPage', 'too_many_pages');
  if (body.lastPage > lastAllowedPage) throw invalid('$.lastPage', 'beyond_lease_pages');
  if (!body.complete && body.lastPage >= lastAllowedPage) throw invalid('$.complete', 'required_at_page_limit');
  if (body.complete && body.totalPages !== undefined && body.totalPages < body.lastPage) throw invalid('$.totalPages', 'below_last_page');
  let bytes = 0;
  let previous: Entry | null = null;
  for (const [index, entry] of body.pages.entries()) {
    if (entry.page < nextPage || entry.page > body.lastPage) throw invalid(`$.pages[${index}].page`, 'out_of_range');
    const samePage = previous?.page === entry.page;
    // Pages ascend; the parts of a page are numbered from 0 without gaps and all travel in the same batch.
    if (previous && (entry.page < previous.page || (samePage && entry.part !== previous.part + 1) || (!samePage && entry.part !== 0))) throw invalid(`$.pages[${index}]`, 'order');
    if (!previous && entry.part !== 0) throw invalid(`$.pages[${index}]`, 'order');
    bytes += encoder.encode(entry.text).byteLength;
    previous = entry;
  }
  if (bytes > MAX_BATCH_TEXT_BYTES) throw invalid('$.pages', 'too_much_text');
}

interface ChunkRow {
  id: string;
  ordinal: number;
  page: number;
  startOffset: number;
  text: string;
  textHash: string;
  method: string;
}

const digestLine = (r: { ordinal: number; page: number; start: number; method: string; hash: string }) => `${r.ordinal}:${r.page}:${r.start}:${r.method}:${r.hash}`;
const digestOf = (lines: string[]) => sha256Hex(lines.join('\n'));

async function buildChunks(lease: LeaseRow, ordinal: number, entries: Entry[]): Promise<ChunkRow[]> {
  const rows: ChunkRow[] = [];
  const pageLength = new Map<number, number>();
  for (const entry of entries) {
    // A page split into parts reads as its parts joined by one newline; offsets continue across the join.
    const base = pageLength.get(entry.page) ?? 0;
    for (const chunk of chunkText(entry.text)) {
      const n = ordinal * ORDINAL_STRIDE + rows.length;
      rows.push({ id: `${lease.attachment_id}:${lease.generation_id}:${n}`, ordinal: n, page: entry.page, startOffset: base + chunk.startOffset, text: chunk.text, textHash: await sha256Hex(chunk.text), method: entry.method });
    }
    pageLength.set(entry.page, base + entry.text.length + 1);
  }
  return rows;
}

async function writeChunks(archive: D1Database, lease: LeaseRow, ordinal: number, rows: ChunkRow[], subject: string, filename: string) {
  const low = ordinal * ORDINAL_STRIDE;
  const high = low + ORDINAL_STRIDE - 1;
  // A retried or half-written batch is rewritten whole, so its rows are exactly what the stored hash describes.
  await archive.batch([
    archive
      .prepare('DELETE FROM source_fts WHERE source_id = ? AND generation_id = ? AND chunk_id IN (SELECT id FROM chunks WHERE source_id = ? AND attachment_id = ? AND generation_id = ? AND ordinal BETWEEN ? AND ?)')
      .bind(lease.source_id, lease.generation_id, lease.source_id, lease.attachment_id, lease.generation_id, low, high),
    archive.prepare('DELETE FROM chunks WHERE source_id = ? AND attachment_id = ? AND generation_id = ? AND ordinal BETWEEN ? AND ?').bind(lease.source_id, lease.attachment_id, lease.generation_id, low, high),
  ]);
  const writes = rows.flatMap((row) => [
    archive
      .prepare('INSERT INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
      .bind(row.id, lease.source_id, lease.attachment_id, lease.generation_id, row.ordinal, row.page, row.startOffset, row.text, row.textHash, row.method),
    archive.prepare('INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES (?, ?, ?, ?, ?, ?)').bind(lease.source_id, row.id, lease.generation_id, subject, row.text, filename),
  ]);
  // D1 limits statements per batch; the slices are independent and the whole batch is rewritten on retry.
  for (let i = 0; i < writes.length; i += 50) await archive.batch(writes.slice(i, i + 50));
}

interface BatchRow {
  ordinal: number;
  batch_hash: string;
  chunks_digest: string;
  last_page: number;
}

/** Removes everything a generation wrote, in the archive and the control tables. Idempotent. */
async function discardGeneration(env: Env, generationId: string, attachmentId: string, sourceId: string): Promise<void> {
  const source = await loadSource(env.CONTROL_DB, sourceId);
  const archive = source ? archiveFor(env, source) : null;
  if (archive) {
    await archive.batch([
      archive.prepare('DELETE FROM source_fts WHERE source_id = ? AND generation_id = ?').bind(sourceId, generationId),
      archive.prepare('DELETE FROM chunks WHERE source_id = ? AND attachment_id = ? AND generation_id = ?').bind(sourceId, attachmentId, generationId),
    ]);
  } else if (source) {
    // A source with a partition we cannot reach keeps its batch rows, so a later pass tries again.
    return;
  }
  await env.CONTROL_DB.prepare('DELETE FROM extraction_batches WHERE generation_id = ?').bind(generationId).run();
}

// POST /device-work/extraction/{leaseId}/batch
export async function submitBatch(env: Env, now: number, body: ReturnType<typeof batchBody>, auth: AuthContext, leaseId: string) {
  const db = env.CONTROL_DB;
  const lease = await loadLease(db, leaseId);
  const live = lease?.state === 'leased' && lease.lease_until >= now;
  // A published lease is readable only to repeat its final batch, so a lost answer can be asked again.
  if (!lease || lease.device_id !== auth.deviceId || lease.lease_token !== body.leaseToken || !(live || lease.state === 'published')) throw leaseLost();
  if (body.contentHash !== lease.content_hash || body.extractorVersion !== lease.extractor_version) throw new HttpError(409, 'input_changed', 'The file changed after this task was assigned.');
  if (body.generationId !== lease.generation_id) throw new HttpError(409, 'generation_mismatch', 'This batch belongs to another generation.');

  const source = await loadSource(db, lease.source_id);
  if (!source || source.state === 'deleting' || source.state === 'deleted') {
    if (lease.state === 'leased') await closeLease(db, lease.id, 'cancelled', now);
    throw new HttpError(410, 'deleted', 'The email was deleted.');
  }
  if (source.state !== 'ready' || source.admission !== 'normal' || source.revision !== lease.source_revision) {
    if (lease.state === 'leased') await closeLease(db, lease.id, 'cancelled', now);
    throw new HttpError(409, 'not_eligible', 'This email can no longer be processed on the phone.');
  }
  const settings = await loadSettings(db);
  if (body.policyRevision !== lease.policy_revision || settings.policyRevision !== lease.policy_revision) {
    if (lease.state === 'leased') await closeLease(db, lease.id, 'cancelled', now);
    throw new HttpError(409, 'policy_changed', 'Inference settings changed. Claim the file again.');
  }
  if (!lease.ocr_allowed && body.pages.some((p) => p.method === 'ocr')) throw new HttpError(409, 'ocr_not_allowed', 'Recognition is not allowed for this email.');

  const entries: Entry[] = body.pages.map((p) => ({ ...p, text: normalizeText(p.text) })).filter((p) => p.text.length > 0);
  const batchHash = await sha256Hex(JSON.stringify([entries.map((e) => [e.page, e.part, e.method, e.confidence ?? null, e.text]), body.lastPage, body.totalPages ?? null, body.complete]));

  const existing = await db.prepare('SELECT batch_hash FROM extraction_batches WHERE generation_id = ? AND ordinal = ?').bind(lease.generation_id, body.batchOrdinal).first<{ batch_hash: string }>();
  const checkpoint = await checkpointFor(db, lease.generation_id);
  if (existing) {
    if (existing.batch_hash !== batchHash) throw new HttpError(409, 'batch_conflict', 'This batch number already holds different text.');
    return { leaseId, batchOrdinal: body.batchOrdinal, status: 'replayed', batchHash, nextBatchOrdinal: checkpoint.nextBatchOrdinal, nextPage: checkpoint.nextPage, published: lease.state === 'published' };
  }
  if (lease.state !== 'leased') throw leaseLost();
  if (body.batchOrdinal !== checkpoint.nextBatchOrdinal) throw new HttpError(409, 'batch_out_of_order', 'Batches are submitted in order.', { expectedBatchOrdinal: checkpoint.nextBatchOrdinal });
  checkStructure(body, checkpoint.nextPage, Math.min(lease.start_page + MAX_DEVICE_PAGES - 1, MAX_DOCUMENT_PAGES));

  const archive = archiveFor(env, source);
  const names = archive ? await archive.prepare('SELECT a.name, s.subject FROM attachments a JOIN sources s ON s.id = a.source_id WHERE a.id = ? AND a.source_id = ?').bind(lease.attachment_id, source.id).first<{ name: string; subject: string }>() : null;
  if (!archive || !names) throw new HttpError(503, 'archive_unavailable', 'The archive is not available right now.');

  const rows = await buildChunks(lease, body.batchOrdinal, entries);
  const chunksDigest = await digestOf(rows.map((r) => digestLine({ ordinal: r.ordinal, page: r.page, start: r.startOffset, method: r.method, hash: r.textHash })));
  await writeChunks(archive, lease, body.batchOrdinal, rows, names.subject, names.name);

  const publish: D1PreparedStatement[] = [];
  let detail: string | null = null;
  if (body.complete) {
    const stored = (await db.prepare('SELECT ordinal, batch_hash, chunks_digest, last_page FROM extraction_batches WHERE generation_id = ? ORDER BY ordinal').bind(lease.generation_id).all<BatchRow>()).results;
    const all = [...stored, { ordinal: body.batchOrdinal, batch_hash: batchHash, chunks_digest: chunksDigest, last_page: body.lastPage }];
    // The manifest covers the batches before this one (the phone cannot know this one's hash until the server answers).
    if ((await sha256Hex(stored.map((b) => b.batch_hash).join('\n'))) !== body.manifestHash) throw new HttpError(409, 'manifest_mismatch', 'The batch list does not match what was stored.');
    // Recompute what the archive actually holds for every batch: a racing or half-written batch must never be published.
    const present = (
      await archive
        .prepare('SELECT ordinal, page, start_offset, method, text_hash FROM chunks WHERE source_id = ? AND attachment_id = ? AND generation_id = ? ORDER BY ordinal')
        .bind(lease.source_id, lease.attachment_id, lease.generation_id)
        .all<{ ordinal: number; page: number; start_offset: number; method: string; text_hash: string }>()
    ).results;
    const lines = new Map<number, string[]>();
    for (const r of present) {
      const batch = Math.floor(r.ordinal / ORDINAL_STRIDE);
      lines.set(batch, [...(lines.get(batch) ?? []), digestLine({ ordinal: r.ordinal, page: r.page, start: r.start_offset, method: r.method, hash: r.text_hash })]);
    }
    for (const b of all) {
      if ((await digestOf(lines.get(b.ordinal) ?? [])) !== b.chunks_digest) {
        await discardGeneration(env, lease.generation_id, lease.attachment_id, lease.source_id);
        await closeLease(db, lease.id, 'cancelled', now);
        throw new HttpError(409, 'generation_corrupt', 'The stored text does not match the batches. Claim the file again.');
      }
    }
    detail = body.totalPages !== undefined && body.totalPages > body.lastPage ? 'limit' : null;
    publish.push(
      db
        .prepare(
          `UPDATE attachment_processing SET state = 'ready', detail = ?, active_generation = ?, pages_read = ?, pages_total = ?, resume_page = NULL, revision = revision + 1
           WHERE attachment_id = ? AND state = 'extracting' AND revision = ?`,
        )
        .bind(detail, lease.generation_id, body.lastPage, body.totalPages ?? body.lastPage, lease.attachment_id, lease.processing_revision),
      attachmentChange(db, lease.attachment_id, lease.processing_revision + 1, now),
      db.prepare("UPDATE extraction_leases SET state = 'published', closed_at = ? WHERE id = ? AND state = 'leased'").bind(now, lease.id),
    );
  }

  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM extraction_leases WHERE id = ? AND lease_token = ? AND state = 'leased' AND lease_until >= ?)", lease.id, lease.lease_token, now),
      assertion(db, "EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND state = 'extracting' AND revision = ?)", lease.attachment_id, lease.processing_revision),
      assertion(db, "EXISTS (SELECT 1 FROM source_registry WHERE id = ? AND state = 'ready' AND admission = 'normal' AND revision = ?)", lease.source_id, lease.source_revision),
      assertion(db, `${POLICY_REVISION_SQL} = ?`, lease.policy_revision),
      assertion(db, '(SELECT COUNT(*) FROM extraction_batches WHERE generation_id = ?) = ?', lease.generation_id, body.batchOrdinal),
      db
        .prepare('INSERT INTO extraction_batches(generation_id, ordinal, attachment_id, source_id, batch_hash, chunks_digest, chunk_count, last_page, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
        .bind(lease.generation_id, body.batchOrdinal, lease.attachment_id, lease.source_id, batchHash, chunksDigest, rows.length, body.lastPage, now),
      db.prepare("UPDATE extraction_leases SET lease_until = ? WHERE id = ? AND state = 'leased'").bind(now + EXTRACTION_LEASE_MS, lease.id),
      ...publish,
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    if (error instanceof Error && /UNIQUE constraint failed/.test(error.message)) throw new HttpError(409, 'batch_conflict', 'This batch number was submitted at the same time.');
    throw error;
  }
  return {
    leaseId,
    batchOrdinal: body.batchOrdinal,
    status: 'accepted',
    batchHash,
    nextBatchOrdinal: body.batchOrdinal + 1,
    nextPage: body.lastPage + 1,
    published: body.complete,
    ...(body.complete ? { attachment: { state: 'ready', detail } } : {}),
  };
}

const OUTCOME_STATE = {
  encrypted: { lease: 'finished', state: 'unsupported', detail: 'encrypted' },
  unsupported: { lease: 'finished', state: 'unsupported', detail: 'unsupported_type' },
  failed: { lease: 'finished', state: 'failed', detail: 'parser_error' },
  unavailable: { lease: 'released', state: 'deferred', detail: 'device_unavailable' },
} as const;

// POST /device-work/extraction/{leaseId}/outcome — the phone could not extract the file, or gave it back.
export async function reportOutcome(env: Env, now: number, body: ReturnType<typeof outcomeBody>, auth: AuthContext, leaseId: string) {
  const db = env.CONTROL_DB;
  const lease = await loadLease(db, leaseId);
  if (!lease || lease.device_id !== auth.deviceId || lease.lease_token !== body.leaseToken || lease.state !== 'leased' || lease.lease_until < now) throw leaseLost();
  // A resumed read that cannot go on leaves the text already published as it was: the document stays readable up to the page
  // it had reached, still offering to read more, instead of being marked unsupported or failed.
  const resumed = lease.base_generation_id !== null && body.outcome !== 'unavailable';
  const target = resumed ? ({ lease: 'finished', state: 'ready', detail: 'limit' } as const) : OUTCOME_STATE[body.outcome];
  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM extraction_leases WHERE id = ? AND lease_token = ? AND state = 'leased' AND lease_until >= ?)", lease.id, lease.lease_token, now),
      assertion(db, "EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND state = 'extracting' AND revision = ?)", lease.attachment_id, lease.processing_revision),
      db.prepare('UPDATE extraction_leases SET state = ?, closed_at = ? WHERE id = ?').bind(target.lease, now, lease.id),
      db
        .prepare('UPDATE attachment_processing SET state = ?, detail = ?, resume_page = CASE WHEN ? THEN NULL ELSE resume_page END, revision = revision + 1 WHERE attachment_id = ? AND state = \'extracting\' AND revision = ?')
        .bind(target.state, target.detail, resumed ? 1 : 0, lease.attachment_id, lease.processing_revision),
      attachmentChange(db, lease.attachment_id, lease.processing_revision + 1, now),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    throw error;
  }
  return { leaseId, attachment: { state: target.state, detail: target.detail } };
}

export const readMoreBody = v.object({});

// POST /attachments/{id}/read-more — the owner asks for the pages after the ones already read. The published text stays as it is
// and stays searchable; the file waits in the queue with the page to continue from, and the next lease starts a new generation.
export async function readMore(env: Env, now: number, _body: ReturnType<typeof readMoreBody>, attachmentId: string) {
  const db = env.CONTROL_DB;
  const row = await db
    .prepare('SELECT source_id, state, detail, revision, pages_read, pages_total FROM attachment_processing WHERE attachment_id = ?')
    .bind(attachmentId)
    .first<{ source_id: string; state: string; detail: string | null; revision: number; pages_read: number | null; pages_total: number | null }>();
  const source = row ? await loadSource(db, row.source_id) : null;
  if (!row || !source || source.state === 'deleting' || source.state === 'deleted') throw notFound();
  if (source.state !== 'ready' || source.admission !== 'normal') throw new HttpError(409, 'not_eligible', 'This email cannot be processed now.');
  if (row.state !== 'ready' || row.detail !== 'limit' || !row.pages_read || !row.pages_total || row.pages_read >= row.pages_total || row.pages_read >= MAX_DOCUMENT_PAGES) {
    throw new HttpError(409, 'nothing_to_read', 'This file has no further pages waiting to be read.', { state: row.state });
  }
  const resumePage = row.pages_read + 1;
  const results = await db.batch([
    db
      .prepare("UPDATE attachment_processing SET state = 'deferred', detail = 'device_unavailable', resume_page = ?, revision = revision + 1 WHERE attachment_id = ? AND revision = ? AND state = 'ready' AND detail = 'limit'")
      .bind(resumePage, attachmentId, row.revision),
    attachmentChange(db, attachmentId, row.revision + 1, now),
  ]);
  // Another request or a state change got there first: the caller asks again with the file as it is now.
  if (results[0]?.meta.changes !== 1) throw new HttpError(409, 'nothing_to_read', 'This file has no further pages waiting to be read.');
  return { attachmentId, state: 'deferred', detail: 'device_unavailable', resumePage };
}

/**
 * Scheduled upkeep: leases whose time ran out return their files, generations that were never published (a lease lost,
 * a policy change, an abandoned run) are removed after a day, and closed leases are forgotten after a month.
 */
export async function maintainExtraction(env: Env, now: number): Promise<void> {
  const db = env.CONTROL_DB;
  await expireLeases(db, now);
  const stale = (
    await db
      .prepare(
        `SELECT b.generation_id, b.attachment_id, b.source_id FROM extraction_batches b
         WHERE NOT EXISTS (SELECT 1 FROM attachment_processing ap WHERE ap.attachment_id = b.attachment_id AND ap.active_generation = b.generation_id)
           AND NOT EXISTS (SELECT 1 FROM extraction_leases l WHERE l.generation_id = b.generation_id AND l.state = 'leased')
         GROUP BY b.generation_id HAVING MAX(b.created_at) < ? LIMIT 10`,
      )
      .bind(now - UNPUBLISHED_GENERATION_MS)
      .all<{ generation_id: string; attachment_id: string; source_id: string }>()
  ).results;
  for (const generation of stale) await discardGeneration(env, generation.generation_id, generation.attachment_id, generation.source_id);
  await db.prepare("DELETE FROM extraction_leases WHERE state <> 'leased' AND closed_at < ?").bind(now - CLOSED_LEASE_KEEP_MS).run();
}
