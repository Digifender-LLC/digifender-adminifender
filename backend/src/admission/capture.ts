import type { Env } from '../env';
import { parseAddrSpec, parseSingleMailbox } from './addresses';
import { type BlockRule, findBlockingRule } from './blocking';

/** Defensive bound; Cloudflare rejects larger inbound messages before the Worker (tdd.md#preflight-and-release-gates). */
export const MAX_RAW_BYTES = 25 * 1024 * 1024;
export const PARSER_JOB_VERSION = 'mime-v1';

export type AdmissionOutcome =
  | { kind: 'blocked'; ruleId: string }
  | { kind: 'too_large' }
  | { kind: 'staged'; sourceId: string; jobId: string }
  | { kind: 'duplicate'; sourceId: string }
  | { kind: 'failed'; stage: 'read' | 'stage' | 'register' };

/** The subset of ForwardableEmailMessage admission uses, so tests can supply synthetic mail. */
export interface InboundEmail {
  readonly from: string;
  readonly to: string;
  readonly headers: Headers;
  readonly raw: ReadableStream<Uint8Array>;
  readonly rawSize: number;
  setReject(reason: string): void;
}

type AdmissionEnv = Pick<Env, 'CONTROL_DB' | 'ARCHIVE_BUCKET' | 'WORK_QUEUE'>;

const encoder = new TextEncoder();

async function sha256Hex(data: ArrayBuffer | Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('');
}

function utcDay(now: number): string {
  return new Date(now).toISOString().slice(0, 10);
}

async function loadBlockRules(db: D1Database): Promise<BlockRule[]> {
  const { results } = await db
    .prepare('SELECT id, kind, match_field, normalized_value FROM block_rules WHERE enabled = 1 ORDER BY created_at, id')
    .all<{ id: string; kind: BlockRule['kind']; match_field: BlockRule['matchField']; normalized_value: string }>();
  return results.map((row) => ({ id: row.id, kind: row.kind, matchField: row.match_field, normalizedValue: row.normalized_value, enabled: true }));
}

async function recordFailure(db: D1Database, stage: string, now: number): Promise<void> {
  try {
    await db
      .prepare("INSERT INTO audit_events(id, kind, entity_id, outcome, metadata_json, created_at) VALUES (?, 'ingest', NULL, 'failed', ?, ?)")
      .bind(crypto.randomUUID(), JSON.stringify({ stage }), now)
      .run();
  } catch {
    // The control database may be the failing dependency; the bounce below is still visible to the sender.
  }
}

/**
 * Email admission steps 1–4 (lld.md#ingestion): manual blocks before the body is read, raw staging in R2,
 * then registry + parse job + outbox in one CONTROL_DB batch. Spam heuristics and parsing run later.
 */
export async function admitEmail(message: InboundEmail, env: AdmissionEnv, now = Date.now()): Promise<AdmissionOutcome> {
  const sender = { envelopeFrom: parseAddrSpec(message.from), headerFrom: parseSingleMailbox(message.headers.get('from')) };
  const blocking = findBlockingRule(await loadBlockRules(env.CONTROL_DB), sender);
  if (blocking) {
    message.setReject('Message rejected by recipient policy.');
    try {
      await env.CONTROL_DB
        .prepare('INSERT INTO block_counters(rule_id, day, count) VALUES (?, ?, 1) ON CONFLICT(rule_id, day) DO UPDATE SET count = count + 1')
        .bind(blocking.id, utcDay(now))
        .run();
    } catch {
      // Telemetry only; the rejection already happened.
    }
    return { kind: 'blocked', ruleId: blocking.id };
  }

  if (message.rawSize > MAX_RAW_BYTES) {
    message.setReject('Message exceeds the accepted size.');
    return { kind: 'too_large' };
  }

  const temporaryFailure = async (stage: 'read' | 'stage' | 'register'): Promise<AdmissionOutcome> => {
    // Cloudflare does not promise sender retry after a failed invocation, so bounce visibly instead of dropping mail.
    message.setReject('Temporary failure storing message; please retry later.');
    await recordFailure(env.CONTROL_DB, stage, now);
    return { kind: 'failed', stage };
  };

  // Buffered within MAX_RAW_BYTES so the staging key can carry the content hash (lld.md#storage R2 prefixes).
  let raw: ArrayBuffer;
  try {
    raw = await new Response(message.raw).arrayBuffer();
  } catch {
    return temporaryFailure('read');
  }
  if (raw.byteLength > MAX_RAW_BYTES) {
    message.setReject('Message exceeds the accepted size.');
    return { kind: 'too_large' };
  }

  const recipient = message.to.trim().toLowerCase();
  const contentHash = await sha256Hex(raw);
  const externalKey = await sha256Hex(encoder.encode(`${recipient}\n${contentHash}`));

  const existing = await env.CONTROL_DB
    .prepare("SELECT id FROM source_registry WHERE kind = 'email' AND external_key = ?")
    .bind(externalKey)
    .first<{ id: string }>();
  if (existing) return { kind: 'duplicate', sourceId: existing.id };

  const sourceId = crypto.randomUUID();
  const jobId = crypto.randomUUID();
  const arrivalId = crypto.randomUUID();
  const stagedKey = `staging/${sourceId}/${contentHash}.eml`;

  try {
    await env.ARCHIVE_BUCKET.put(stagedKey, raw, {
      sha256: contentHash,
      httpMetadata: { contentType: 'message/rfc822' },
      // Lets the reconciler find objects whose registry insert never committed.
      customMetadata: { recipientHash: await sha256Hex(encoder.encode(recipient)), arrivalId, receivedAt: String(now) },
    });
  } catch {
    return temporaryFailure('stage');
  }

  let inserted: boolean;
  try {
    const db = env.CONTROL_DB;
    const results = await db.batch([
      db
        .prepare(
          "INSERT INTO source_registry(id, kind, external_key, content_hash, admission, privacy, state, revision, staged_key, received_at) VALUES (?, 'email', ?, ?, 'normal', 'hybrid', 'staged', 1, ?, ?) ON CONFLICT(kind, external_key) DO NOTHING",
        )
        .bind(sourceId, externalKey, contentHash, stagedKey, now),
      // Guarded on the source row this batch inserted, so a lost race creates no orphan job.
      db
        .prepare(
          "INSERT INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) SELECT ?, ?, 'parse', ?, 1, 'pending', ?, ?, ? WHERE EXISTS (SELECT 1 FROM source_registry WHERE id = ?)",
        )
        .bind(jobId, `parse:${sourceId}:${PARSER_JOB_VERSION}`, sourceId, now, JSON.stringify({ sourceId }), now, sourceId),
      db
        .prepare("INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, ?, 'job', ?, 'pending', ? WHERE EXISTS (SELECT 1 FROM jobs WHERE id = ?)")
        .bind(crypto.randomUUID(), `job:${jobId}`, jobId, now, jobId),
    ]);
    inserted = (results[0]?.meta.changes ?? 0) === 1;
  } catch {
    // The staged object stays; its metadata makes it discoverable for reconciliation.
    return temporaryFailure('register');
  }

  if (!inserted) {
    await env.ARCHIVE_BUCKET.delete(stagedKey).catch(() => undefined);
    const winner = await env.CONTROL_DB
      .prepare("SELECT id FROM source_registry WHERE kind = 'email' AND external_key = ?")
      .bind(externalKey)
      .first<{ id: string }>();
    return { kind: 'duplicate', sourceId: winner?.id ?? '' };
  }

  // Best-effort publish. A failure leaves the outbox row pending for the scheduled reconciler.
  try {
    await env.WORK_QUEUE.send({ version: 1, jobId });
    await env.CONTROL_DB.prepare("UPDATE outbox SET state = 'sent' WHERE dedupe_key = ? AND state = 'pending'").bind(`job:${jobId}`).run();
  } catch {
    // Intake is complete once the registry batch commits.
  }
  return { kind: 'staged', sourceId, jobId };
}
