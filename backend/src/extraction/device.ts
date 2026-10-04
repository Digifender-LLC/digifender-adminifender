import type { AuthContext } from '../auth/sessions';
import { chunkText } from '../archive/normalize';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';
import { sha256Hex } from '../ids';
import { THERMAL_STATES } from '../inference/routing';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';

/**
 * Device PDF and image extraction (lld.md#text-extraction-and-ocr). The server leases one deferred attachment to a
 * charging phone, keeps a page checkpoint, stores each immutable batch and publishes the generation only through a
 * fenced control update. A phone never receives anything but the attachment it was leased.
 */
export const DEVICE_EXTRACTOR_VERSION = 'device-extract-v1';
export const EXTRACTION_LEASE_MS = 10 * 60 * 1000;
export const MAX_PAGES_PER_BATCH = 10;
export const MAX_BATCH_TEXT_BYTES = 128 * 1024;
export const MAX_PAGES_PER_JOB = 100;
const MIN_BATTERY = 30;
const NO_OCR = ['no_ai', 'restricted_metadata'];

export const deviceGenerationFor = (attachmentId: string) => `att:${attachmentId}:${DEVICE_EXTRACTOR_VERSION}`;
/** Deterministic chunk identity: a repeated batch rewrites the same rows instead of adding new ones. */
const chunkIdFor = (attachmentId: string, generation: string, page: number, part: number) => `${attachmentId}:${generation}:${page}:${part}`;
/** Ordinals sort by page, then by part within the page. */
const ordinalFor = (page: number, part: number) => page * 1000 + part;

const leaseLost = () => new HttpError(409, 'lease_lost', 'This attachment is no longer assigned to this device.');

export const claimBody = v.object({
  charging: v.boolean(),
  batteryPercent: v.integer({ min: 0, max: 100 }),
  thermal: v.oneOf(THERMAL_STATES),
});

interface Candidate {
  attachment_id: string;
  source_id: string;
  revision: number;
  policy_revision: number;
  privacy: string;
}

/** The eligible source behind an attachment: ready, admitted and not deleted. */
async function eligibleSource(env: Env, sourceId: string): Promise<RegistryRow | null> {
  const row = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(sourceId).first<RegistryRow>();
  return row && row.state === 'ready' && row.admission === 'normal' ? row : null;
}

function attachmentChange(db: D1Database, attachmentId: string, now: number, guard: string, params: unknown[]): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at)
       SELECT 'attachment', attachment_id, revision, 0, json_object('id', attachment_id, 'sourceId', source_id, 'state', state, 'detail', detail, 'revision', revision), ?
       FROM attachment_processing WHERE attachment_id = ? AND ${guard}`,
    )
    .bind(now, attachmentId, ...params);
}

/**
 * POST /device-extraction/claim — charging-only background work below severe thermal state with at least 30 %
 * battery. Leases the oldest deferred PDF or image waiting for a phone, or one the owner asked to resume.
 */
export async function claimExtraction(env: Env, now: number, body: ReturnType<typeof claimBody>, auth: AuthContext) {
  if (!body.charging || body.batteryPercent < MIN_BATTERY || THERMAL_STATES.indexOf(body.thermal) >= THERMAL_STATES.indexOf('severe')) return { work: null, reason: 'not_ready' };
  const db = env.CONTROL_DB;
  const busy = await db.prepare('SELECT 1 AS hit FROM extraction_leases WHERE device_id = ? AND lease_until >= ?').bind(auth.deviceId, now).first();
  if (busy) return { work: null, reason: 'busy' };

  const { results } = await db
    .prepare(
      `SELECT ap.attachment_id, ap.source_id, ap.revision, ap.policy_revision, r.privacy FROM attachment_processing ap JOIN source_registry r ON r.id = ap.source_id
       LEFT JOIN extraction_leases l ON l.attachment_id = ap.attachment_id
       WHERE r.state = 'ready' AND r.admission = 'normal'
         AND ((ap.state = 'deferred' AND ap.detail IN ('device_unavailable', 'resume_requested')) OR (ap.state = 'extracting' AND l.lease_until < ?))
       ORDER BY ap.rowid LIMIT 10`,
    )
    .bind(now)
    .all<Candidate>();

  for (const candidate of results) {
    const source = await eligibleSource(env, candidate.source_id);
    const archive = source ? archiveFor(env, source) : null;
    const attachment = archive ? await archive.prepare('SELECT id, name, declared_type, size_bytes, sha256 FROM attachments WHERE id = ?').bind(candidate.attachment_id).first<{ id: string; name: string; declared_type: string | null; size_bytes: number; sha256: string }>() : null;
    if (!source || !attachment) continue;
    const token = crypto.randomUUID();
    const generation = deviceGenerationFor(candidate.attachment_id);
    const guard = 'revision = ? AND policy_revision = ?';
    try {
      await db.batch([
        assertion(db, `EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND ${guard})`, candidate.attachment_id, candidate.revision, candidate.policy_revision),
        // A new policy revision restarts the checkpoint; otherwise the phone resumes where the last one stopped.
        db.prepare(
          `INSERT INTO extraction_leases(attachment_id, generation_id, content_hash, policy_revision, lease_token, device_id, lease_until, job_pages)
           VALUES (?, ?, ?, ?, ?, ?, ?, 0)
           ON CONFLICT(attachment_id) DO UPDATE SET lease_token = excluded.lease_token, device_id = excluded.device_id, lease_until = excluded.lease_until, job_pages = 0,
             next_batch = CASE WHEN extraction_leases.policy_revision = excluded.policy_revision AND extraction_leases.content_hash = excluded.content_hash THEN extraction_leases.next_batch ELSE 0 END,
             next_page = CASE WHEN extraction_leases.policy_revision = excluded.policy_revision AND extraction_leases.content_hash = excluded.content_hash THEN extraction_leases.next_page ELSE 1 END,
             policy_revision = excluded.policy_revision, content_hash = excluded.content_hash
           WHERE extraction_leases.lease_until IS NULL OR extraction_leases.lease_until < ?`,
        ).bind(candidate.attachment_id, generation, attachment.sha256, candidate.policy_revision, token, auth.deviceId, now + EXTRACTION_LEASE_MS, now),
        assertion(db, 'EXISTS (SELECT 1 FROM extraction_leases WHERE attachment_id = ? AND lease_token = ?)', candidate.attachment_id, token),
        db.prepare("UPDATE attachment_processing SET state = 'extracting', detail = NULL, revision = revision + 1 WHERE attachment_id = ?").bind(candidate.attachment_id),
        attachmentChange(db, candidate.attachment_id, now, 'revision = ?', [candidate.revision + 1]),
      ]);
    } catch (error) {
      if (isAssertionFailure(error)) continue;
      throw error;
    }
    const lease = await db.prepare('SELECT next_batch, next_page FROM extraction_leases WHERE attachment_id = ?').bind(candidate.attachment_id).first<{ next_batch: number; next_page: number }>();
    const stored = await db
      .prepare('SELECT batch_hash FROM extraction_batches WHERE attachment_id = ? AND generation_id = ? AND batch_ordinal < ? ORDER BY batch_ordinal')
      .bind(candidate.attachment_id, generation, lease!.next_batch)
      .all<{ batch_hash: string }>();
    return {
      work: {
        attachmentId: candidate.attachment_id,
        sourceId: candidate.source_id,
        name: attachment.name,
        declaredType: attachment.declared_type,
        sizeBytes: attachment.size_bytes,
        contentHash: attachment.sha256,
        policyRevision: candidate.policy_revision,
        generationId: generation,
        leaseToken: token,
        expiresAt: new Date(now + EXTRACTION_LEASE_MS).toISOString(),
        nextBatch: lease!.next_batch,
        nextPage: lease!.next_page,
        maxPages: MAX_PAGES_PER_JOB,
        // no_ai and restricted report mail forbid OCR; deterministic PDF text stays allowed (lld.md#text-extraction-and-ocr).
        ocrAllowed: !NO_OCR.includes(candidate.privacy),
        // A resumed job needs the earlier batches' hashes for the closing manifest.
        batchHashes: stored.results.map((r) => r.batch_hash),
      },
    };
  }
  return { work: null, reason: 'no_work' };
}

interface LeaseRow {
  attachment_id: string;
  generation_id: string;
  content_hash: string;
  policy_revision: number;
  lease_token: string | null;
  device_id: string | null;
  lease_until: number | null;
  next_batch: number;
  next_page: number;
  job_pages: number;
}

async function liveLease(env: Env, attachmentId: string, token: string, auth: AuthContext, now: number): Promise<LeaseRow> {
  const lease = await env.CONTROL_DB.prepare('SELECT * FROM extraction_leases WHERE attachment_id = ?').bind(attachmentId).first<LeaseRow>();
  if (!lease || lease.lease_token !== token || lease.device_id !== auth.deviceId || (lease.lease_until ?? 0) < now) throw leaseLost();
  return lease;
}

const leaseToken = v.uuid();
export const heartbeatBody = v.object({ leaseToken });

// POST /device-extraction/{attachmentId}/heartbeat
export async function extractionHeartbeat(env: Env, now: number, body: ReturnType<typeof heartbeatBody>, auth: AuthContext, attachmentId: string) {
  const result = await env.CONTROL_DB.prepare('UPDATE extraction_leases SET lease_until = ? WHERE attachment_id = ? AND lease_token = ? AND device_id = ? AND lease_until >= ?')
    .bind(now + EXTRACTION_LEASE_MS, attachmentId, body.leaseToken, auth.deviceId, now)
    .run();
  if (result.meta.changes !== 1) throw leaseLost();
  return { attachmentId, expiresAt: new Date(now + EXTRACTION_LEASE_MS).toISOString() };
}

const pageShape = v.object({
  page: v.integer({ min: 1, max: 100_000 }),
  text: v.string({ max: MAX_BATCH_TEXT_BYTES }),
  method: v.oneOf(['pdf_text', 'ocr'] as const),
  confidence: v.optional(v.nullable(v.number({ min: 0, max: 1 }))),
});

export const batchBody = v.object({
  leaseToken,
  contentHash: v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ }),
  policyRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }),
  generationId: v.string({ max: 200 }),
  batchOrdinal: v.integer({ min: 0, max: 100_000 }),
  pages: v.array(pageShape, { max: MAX_PAGES_PER_BATCH }),
  /** True on the last batch: every page is done and [manifestHash] covers all batches in order. */
  complete: v.boolean(),
  /** Set with `complete: false` when the per-job page limit is reached; the rest waits for the owner to resume. */
  paused: v.optional(v.boolean()),
  manifestHash: v.optional(v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ })),
});

/** The batch's identity hash: the same ordinal must always carry exactly the same pages. */
export async function batchHash(pages: { page: number; text: string; method: string }[]): Promise<string> {
  return sha256Hex(canonicalJson(pages.map((p) => ({ page: p.page, text: p.text, method: p.method }))));
}

/**
 * Hash over the ordered batch hashes, sent with the final batch so the server publishes exactly what it acknowledged.
 * Each batch response returns its hash, so the phone never has to reproduce this JSON canonicalization.
 */
export async function batchManifestHash(hashes: string[]): Promise<string> {
  return sha256Hex(hashes.join('\n'));
}

/**
 * POST /device-extraction/{attachmentId}/batch — writes immutable generation chunks first, then advances the
 * checkpoint under the lease. The final batch publishes the generation in a fenced control update that checks the
 * lease, policy revision and content hash; an `extracting` row without a live lease cannot be published.
 */
export async function submitBatch(env: Env, now: number, body: ReturnType<typeof batchBody>, auth: AuthContext, attachmentId: string) {
  const db = env.CONTROL_DB;
  const lease = await liveLease(env, attachmentId, body.leaseToken, auth, now);
  if (body.contentHash !== lease.content_hash) throw new HttpError(409, 'input_changed', 'The attachment does not match the one assigned.');
  if (body.generationId !== lease.generation_id) throw new HttpError(409, 'generation_mismatch', 'This batch is for a different generation.');
  const processing = await db.prepare('SELECT source_id, policy_revision, state FROM attachment_processing WHERE attachment_id = ?').bind(attachmentId).first<{ source_id: string; policy_revision: number; state: string }>();
  if (!processing || processing.state !== 'extracting') throw leaseLost();
  if (body.policyRevision !== lease.policy_revision || processing.policy_revision !== lease.policy_revision) throw new HttpError(409, 'policy_changed', 'Privacy settings changed. Claim the attachment again.');
  const source = await eligibleSource(env, processing.source_id);
  if (!source) throw new HttpError(410, 'deleted', 'The email is no longer available.');
  if (NO_OCR.includes(source.privacy) && body.pages.some((p) => p.method === 'ocr')) throw new HttpError(422, 'policy_blocked', 'OCR is not allowed for this email.');

  const bytes = body.pages.reduce((sum, p) => sum + new TextEncoder().encode(p.text).byteLength, 0);
  if (bytes > MAX_BATCH_TEXT_BYTES) throw new HttpError(413, 'batch_too_large', 'A batch carries at most 128 KiB of text.');
  const pages = [...body.pages].sort((a, b) => a.page - b.page);
  if (new Set(pages.map((p) => p.page)).size !== pages.length) throw new HttpError(400, 'invalid_request', 'Pages repeat within the batch.', { path: '$.pages', reason: 'duplicate' });
  const hash = await batchHash(pages);

  // Batch identity is immutable: a replay is accepted only with identical content, and only in order.
  const existing = await db.prepare('SELECT batch_hash FROM extraction_batches WHERE attachment_id = ? AND generation_id = ? AND batch_ordinal = ?').bind(attachmentId, lease.generation_id, body.batchOrdinal).first<{ batch_hash: string }>();
  if (existing && existing.batch_hash !== hash) throw new HttpError(409, 'batch_conflict', 'This batch number was already used for different pages.');
  if (!existing && body.batchOrdinal !== lease.next_batch) throw new HttpError(409, 'batch_out_of_order', 'Send the next batch in order.', { nextBatch: lease.next_batch, nextPage: lease.next_page });
  if (!existing && pages.length > 0 && pages[0]!.page < lease.next_page) throw new HttpError(409, 'batch_out_of_order', 'These pages were already stored.', { nextBatch: lease.next_batch, nextPage: lease.next_page });

  const archive = archiveFor(env, source);
  if (!archive) throw new HttpError(503, 'unavailable', 'Part of the archive is unavailable.', {}, true);
  const subject = (await archive.prepare('SELECT s.subject, a.name FROM attachments a JOIN sources s ON s.id = a.source_id WHERE a.id = ?').bind(attachmentId).first<{ subject: string; name: string }>())!;
  const writes: D1PreparedStatement[] = [];
  for (const page of pages) {
    const text = page.text.replace(/[ \t]+$/gm, '').trim();
    for (const part of chunkText(text)) {
      const id = chunkIdFor(attachmentId, lease.generation_id, page.page, part.ordinal);
      writes.push(
        archive.prepare('INSERT OR IGNORE INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
          .bind(id, source.id, attachmentId, lease.generation_id, ordinalFor(page.page, part.ordinal), page.page, part.startOffset, part.text, await sha256Hex(part.text), page.method),
        archive.prepare('DELETE FROM source_fts WHERE chunk_id = ?').bind(id),
        archive.prepare('INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES (?, ?, ?, ?, ?, ?)').bind(source.id, id, lease.generation_id, subject.subject, part.text, subject.name),
      );
    }
  }
  // Archive writes are idempotent and stay unreadable until the control pointer names this generation.
  for (let i = 0; i < writes.length; i += 48) await archive.batch(writes.slice(i, i + 48));

  const live = assertion(db, 'EXISTS (SELECT 1 FROM extraction_leases WHERE attachment_id = ? AND lease_token = ? AND lease_until >= ?)', attachmentId, body.leaseToken, now);
  const lastPage = pages.at(-1)?.page ?? lease.next_page - 1;
  const advance = existing
    ? []
    : [
        db.prepare('INSERT INTO extraction_batches(attachment_id, generation_id, batch_ordinal, batch_hash, first_page, last_page, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
          .bind(attachmentId, lease.generation_id, body.batchOrdinal, hash, pages[0]?.page ?? lease.next_page, lastPage, now),
        db.prepare('UPDATE extraction_leases SET next_batch = next_batch + 1, next_page = ?, job_pages = job_pages + ?, lease_until = ? WHERE attachment_id = ? AND lease_token = ?')
          .bind(lastPage + 1, pages.length, now + EXTRACTION_LEASE_MS, attachmentId, body.leaseToken),
      ];

  if (!body.complete && !body.paused) {
    try {
      await db.batch([live, ...advance]);
    } catch (error) {
      if (isAssertionFailure(error)) throw leaseLost();
      throw error;
    }
    return { attachmentId, state: 'extracting', batchHash: hash, nextBatch: existing ? lease.next_batch : lease.next_batch + 1, nextPage: existing ? lease.next_page : lastPage + 1 };
  }

  if (body.complete) {
    const stored = (await db.prepare('SELECT batch_hash FROM extraction_batches WHERE attachment_id = ? AND generation_id = ? ORDER BY batch_ordinal').bind(attachmentId, lease.generation_id).all<{ batch_hash: string }>()).results.map((r) => r.batch_hash);
    const expected = await batchManifestHash(existing ? stored : [...stored, hash]);
    if (body.manifestHash !== expected) throw new HttpError(409, 'manifest_mismatch', 'The batch manifest does not match the stored batches.');
  }
  const [state, detail] = body.complete ? ['ready', null] : ['deferred', 'pages_remaining'];
  // Fenced publication: lease, policy and input hash are checked in the same batch as the pointer change.
  const fence = 'state = \'extracting\' AND policy_revision = ? AND EXISTS (SELECT 1 FROM extraction_leases l WHERE l.attachment_id = attachment_processing.attachment_id AND l.lease_token = ? AND l.content_hash = ? AND l.lease_until >= ?)';
  try {
    await db.batch([
      live,
      ...advance,
      assertion(db, `EXISTS (SELECT 1 FROM attachment_processing WHERE attachment_id = ? AND ${fence})`, attachmentId, lease.policy_revision, body.leaseToken, lease.content_hash, now),
      db.prepare(`UPDATE attachment_processing SET state = ?, detail = ?, active_generation = ?, revision = revision + 1 WHERE attachment_id = ? AND ${fence}`)
        .bind(state, detail, lease.generation_id, attachmentId, lease.policy_revision, body.leaseToken, lease.content_hash, now),
      attachmentChange(db, attachmentId, now, 'state = ?', [state]),
      // The lease ends; the checkpoint stays so a resumed job continues after the last stored page.
      db.prepare('UPDATE extraction_leases SET lease_token = NULL, device_id = NULL, lease_until = NULL WHERE attachment_id = ?').bind(attachmentId),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    throw error;
  }
  return { attachmentId, state, detail, generationId: lease.generation_id, batchHash: hash };
}

export const failBody = v.object({ leaseToken, reason: v.oneOf(['encrypted', 'unreadable', 'limit'] as const) });

/** POST /device-extraction/{attachmentId}/fail — the phone cannot read this file; the original stays downloadable. */
export async function failExtraction(env: Env, now: number, body: ReturnType<typeof failBody>, auth: AuthContext, attachmentId: string) {
  const db = env.CONTROL_DB;
  await liveLease(env, attachmentId, body.leaseToken, auth, now);
  const [state, detail] = body.reason === 'encrypted' ? ['unsupported', 'encrypted'] : body.reason === 'limit' ? ['failed', 'limit'] : ['failed', 'parser_error'];
  try {
    await db.batch([
      assertion(db, 'EXISTS (SELECT 1 FROM extraction_leases WHERE attachment_id = ? AND lease_token = ? AND lease_until >= ?)', attachmentId, body.leaseToken, now),
      db.prepare("UPDATE attachment_processing SET state = ?, detail = ?, revision = revision + 1 WHERE attachment_id = ? AND state = 'extracting'").bind(state, detail, attachmentId),
      attachmentChange(db, attachmentId, now, 'state = ?', [state]),
      db.prepare('UPDATE extraction_leases SET lease_token = NULL, device_id = NULL, lease_until = NULL WHERE attachment_id = ?').bind(attachmentId),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    throw error;
  }
  return { attachmentId, state, detail };
}

export const resumeBody = v.object({});

/** POST /attachments/{id}/resume-extraction — the owner's "Resume next pages" for a paused long document. */
export async function resumeExtraction(env: Env, now: number, _body: ReturnType<typeof resumeBody>, attachmentId: string) {
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT source_id, state, detail FROM attachment_processing WHERE attachment_id = ?').bind(attachmentId).first<{ source_id: string; state: string; detail: string | null }>();
  if (!row || !(await eligibleSource(env, row.source_id))) throw notFound();
  if (row.state !== 'deferred' || row.detail !== 'pages_remaining') throw new HttpError(409, 'not_paused', 'This attachment has no remaining pages waiting.', { state: row.state });
  await db.batch([
    db.prepare("UPDATE attachment_processing SET detail = 'resume_requested', revision = revision + 1 WHERE attachment_id = ? AND state = 'deferred' AND detail = 'pages_remaining'").bind(attachmentId),
    attachmentChange(db, attachmentId, now, "detail = 'resume_requested'", []),
  ]);
  return { attachmentId, state: 'deferred', detail: 'resume_requested' };
}
