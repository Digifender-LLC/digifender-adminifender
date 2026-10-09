import { parseSingleMailbox } from '../admission/addresses';
import { scoreSpam } from '../admission/spam';
import type { Env } from '../env';
import { deterministicUuid, sha256Hex } from '../ids';
import { isReportMail, reportJobStatements } from '../reports/job';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { extractionJobStatements } from '../extraction/job';
import { messageIdOf, threadReferences, triageJobStatements } from '../triage/job';
import { linkFacts } from './links';
import { type NormalizedEmail, normalizeEmail } from './normalize';
import { writablePartition } from './partitions';

/** Inline text bound per source row; larger text lives in R2 with bounded chunks (lld.md#storage). */
export const MAX_INLINE_TEXT_BYTES = 256 * 1024;
export const BODY_GENERATION = 'body-v1';

interface SourceRow {
  id: string;
  state: string;
  revision: number;
  staged_key: string | null;
  content_hash: string;
  received_at: number;
  privacy: string;
}

const encoder = new TextEncoder();

/** Spam inputs available today. Transport authentication and confirmed-spam samples are not wired yet and score 0. */
async function admission(env: Env, normalized: NormalizedEmail) {
  const froms = normalized.headers.filter((h) => h.name.toLowerCase() === 'from');
  const sender = froms.length === 1 ? parseSingleMailbox(froms[0]?.value ?? null) : null;
  let override = false;
  if (sender) {
    const row = await env.CONTROL_DB.prepare(
      "SELECT 1 AS hit FROM spam_overrides WHERE enabled = 1 AND verdict = 'quarantine' AND ((kind = 'address' AND normalized_value = ?) OR (kind = 'domain' AND normalized_value = ?)) LIMIT 1",
    )
      .bind(`${sender.local}@${sender.domain}`, sender.domain)
      .first();
    override = row !== null;
  }
  const links = linkFacts(normalized.html, normalized.text);
  return scoreSpam({
    text: normalized.text,
    malformedFrom: sender === null,
    linkHosts: links.hosts,
    linkDomainMismatch: links.mismatchedLiteralDomain,
    ownerQuarantineOverride: override,
    authentication: { spf: 'unknown', dkim: 'unknown' },
    confirmedSpamBody: false,
    trustedAuthenticatedSender: false,
  });
}

/**
 * Parse job: staged MIME → archive rows, chunks, FTS and receipt (idempotent), then one fenced CONTROL_DB batch
 * that publishes `ready` with final admission (lld.md#ingestion steps 5–8, lld.md#jobs archive saga).
 */
export async function parseJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  if (!job.sourceId) return { kind: 'fail', errorCode: 'missing_source' };
  const source = await env.CONTROL_DB.prepare('SELECT id, state, revision, staged_key, content_hash, received_at, privacy FROM source_registry WHERE id = ?')
    .bind(job.sourceId)
    .first<SourceRow>();
  if (!source || source.state === 'deleting' || source.state === 'deleted') return { kind: 'complete', output: { skipped: 'deleted' } };
  if (source.state === 'ready') return { kind: 'complete', output: { skipped: 'already_ready' } };
  if (!source.staged_key) return { kind: 'fail', errorCode: 'original_raw_unavailable' };

  const object = await env.ARCHIVE_BUCKET.get(source.staged_key);
  if (!object) return { kind: 'fail', errorCode: 'original_raw_unavailable' };
  const raw = new Uint8Array(await object.arrayBuffer());
  const mailboxHash = object.customMetadata?.recipientHash ?? null;
  if ((await sha256Hex(raw)) !== source.content_hash) return { kind: 'fail', errorCode: 'staging_hash_mismatch' };

  const [guardId, guardToken] = guardParams(job);
  let normalized: NormalizedEmail;
  try {
    normalized = await normalizeEmail(raw);
  } catch {
    // Raw bytes stay staged for repair; the failure is visible.
    return {
      kind: 'fail',
      errorCode: 'parse_error',
      statements: [
        env.CONTROL_DB.prepare(`UPDATE source_registry SET state = 'parse_failed', revision = revision + 1 WHERE id = ? AND state IN ('staged', 'pending_index') AND ${CLAIM_GUARD}`)
          .bind(source.id, guardId, guardToken),
      ],
    };
  }

  const partition = await writablePartition(env);
  if (!partition) {
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'pending_index' WHERE id = ? AND state = 'staged'").bind(source.id).run();
    return { kind: 'retry', errorCode: 'no_writable_partition' };
  }

  const verdict = await admission(env, normalized);
  const sourceId = source.id;
  // Report mail is restricted before triage, AI or OCR can see it, whatever its signature turns out to be (lld.md#reports).
  const reportMail = await isReportMail(env, normalized.attachments.map((a) => a.name), mailboxHash);
  const privacy = reportMail ? 'restricted_metadata' : source.privacy;

  // 1. R2 objects (per-source paths, content-addressed so retries overwrite identical bytes).
  const attachments = await Promise.all(
    normalized.attachments.map(async (attachment, index) => ({
      ...attachment,
      id: await deterministicUuid(`${sourceId}:attachment:${index}:${attachment.sha256}`),
    })),
  );
  for (const attachment of attachments) {
    await env.ARCHIVE_BUCKET.put(`attachments/${sourceId}/${attachment.id}/${attachment.sha256}`, attachment.bytes, {
      sha256: attachment.sha256,
      httpMetadata: { contentType: 'application/octet-stream' },
    });
  }
  const textBytes = encoder.encode(normalized.text);
  let textObjectKey: string | null = null;
  if (textBytes.byteLength > MAX_INLINE_TEXT_BYTES) {
    textObjectKey = `text/${sourceId}/${normalized.textHash}.txt`;
    await env.ARCHIVE_BUCKET.put(textObjectKey, textBytes, { sha256: normalized.textHash, httpMetadata: { contentType: 'text/plain; charset=utf-8' } });
  }

  // 2. Archive rows, FTS and receipt in one partition batch; INSERT OR IGNORE and FTS replacement keep retries idempotent.
  const manifest = {
    sourceId,
    stagingHash: source.content_hash,
    textHash: normalized.textHash,
    textObjectKey,
    attachments: attachments.map((a) => ({ id: a.id, sha256: a.sha256, sizeBytes: a.sizeBytes })),
    chunks: normalized.chunks.length,
  };
  const manifestHash = await sha256Hex(JSON.stringify(manifest));
  const db = partition.db;
  const filenames = attachments.map((a) => a.name).join(' ');
  const provenance = { parser: normalized.parserVersion, admission: { detector: verdict.detector, score: verdict.score, features: verdict.features }, headersTruncated: normalized.headersTruncated };
  await db.batch([
    db.prepare(
      'INSERT OR IGNORE INTO sources(id, kind, received_at, subject, envelope_json, headers_json, normalized_text, text_object_key, text_hash, provenance_json, parser_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
    ).bind(sourceId, 'email', source.received_at, normalized.subject, JSON.stringify(normalized.envelope), JSON.stringify(normalized.headers), textObjectKey ? null : normalized.text, textObjectKey, normalized.textHash, JSON.stringify(provenance), normalized.parserVersion),
    ...attachments.map((a) =>
      db.prepare('INSERT OR IGNORE INTO attachments(id, source_id, name, declared_type, detected_type, size_bytes, sha256, object_key, disposition) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)')
        .bind(a.id, sourceId, a.name, a.declaredType, a.sizeBytes, a.sha256, `attachments/${sourceId}/${a.id}/${a.sha256}`, a.disposition),
    ),
    ...normalized.chunks.map((chunk) =>
      db.prepare('INSERT OR IGNORE INTO chunks(id, source_id, attachment_id, generation_id, ordinal, page, start_offset, text, text_hash, method) VALUES (?, ?, NULL, ?, ?, NULL, ?, ?, ?, ?)')
        .bind(`${sourceId}:${BODY_GENERATION}:${chunk.ordinal}`, sourceId, BODY_GENERATION, chunk.ordinal, chunk.startOffset, chunk.text, chunk.textHash, 'mime'),
    ),
    db.prepare('DELETE FROM source_fts WHERE source_id = ? AND generation_id = ?').bind(sourceId, BODY_GENERATION),
    ...normalized.chunks.map((chunk) =>
      db.prepare('INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES (?, ?, ?, ?, ?, ?)')
        .bind(sourceId, `${sourceId}:${BODY_GENERATION}:${chunk.ordinal}`, BODY_GENERATION, normalized.subject, chunk.text, chunk.ordinal === 0 ? filenames : ''),
    ),
    db.prepare('INSERT OR IGNORE INTO archive_receipts(source_id, staging_hash, manifest_hash, committed_at) VALUES (?, ?, ?, ?)').bind(sourceId, source.content_hash, manifestHash, now),
  ]);
  const receipt = await db.prepare('SELECT manifest_hash FROM archive_receipts WHERE source_id = ?').bind(sourceId).first<{ manifest_hash: string }>();
  if (receipt?.manifest_hash !== manifestHash) return { kind: 'fail', errorCode: 'archive_receipt_mismatch' };

  // 3. Control publication, fenced by the claim: ready pointer, admission, change record, attachment state.
  const admissionValue = verdict.quarantine ? 'quarantine' : 'normal';
  const control = env.CONTROL_DB;
  // Same shape as every later source projection (sourceProjection), so a synced phone has the list fields from the start.
  const projection = JSON.stringify({
    id: sourceId,
    kind: 'email',
    state: 'ready',
    admission: admissionValue,
    privacy,
    revision: source.revision + 1,
    receivedAt: new Date(source.received_at).toISOString(),
    subject: normalized.subject,
    from: normalized.envelope.from,
    to: normalized.envelope.to,
  });
  return {
    kind: 'complete',
    output: { partitionId: partition.id, admission: admissionValue, manifestHash },
    statements: [
      control.prepare(
        `UPDATE source_registry SET state = 'ready', partition_id = ?, admission = ?, privacy = ?, privacy_floor = CASE WHEN ? THEN 'restricted_metadata' ELSE privacy_floor END, revision = revision + 1
         WHERE id = ? AND state IN ('staged', 'pending_index') AND ${CLAIM_GUARD}`,
      ).bind(partition.id, admissionValue, privacy, reportMail ? 1 : 0, sourceId, guardId, guardToken),
      control.prepare(
        `INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) SELECT 'source', ?, revision, 0, ?, ? FROM source_registry WHERE id = ? AND state = 'ready' AND ready_seq IS NULL AND ${CLAIM_GUARD}`,
      ).bind(sourceId, projection, now, sourceId, guardId, guardToken),
      control.prepare(
        "UPDATE source_registry SET ready_seq = (SELECT MAX(seq) FROM changes WHERE entity_type = 'source' AND entity_id = ?) WHERE id = ? AND state = 'ready' AND ready_seq IS NULL",
      ).bind(sourceId, sourceId),
      // Thread references are recorded for every published source so later replies can group with it.
      ...(mailboxHash
        ? [
            control.prepare(`INSERT OR IGNORE INTO source_threads(source_id, mailbox_hash, message_id, references_json) SELECT ?, ?, ?, ? WHERE ${CLAIM_GUARD}`)
              .bind(sourceId, mailboxHash, messageIdOf(normalized.envelope.messageId), JSON.stringify(threadReferences(normalized.envelope.inReplyTo, normalized.envelope.references)), guardId, guardToken),
          ]
        : []),
      ...triageJobStatements(control, sourceId, now, CLAIM_GUARD, [guardId, guardToken]),
      ...attachments.map((a) =>
        control.prepare(`INSERT OR IGNORE INTO attachment_processing(attachment_id, source_id, revision, state, policy_revision) SELECT ?, ?, 1, 'stored', 0 WHERE ${CLAIM_GUARD}`)
          .bind(a.id, sourceId, guardId, guardToken),
      ),
      ...extractionJobStatements(control, sourceId, now, CLAIM_GUARD, [guardId, guardToken]),
      ...(reportMail ? reportJobStatements(control, sourceId, mailboxHash, now, CLAIM_GUARD, [guardId, guardToken]) : []),
    ],
  };
}
