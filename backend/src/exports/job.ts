import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';
import { sha256Hex } from '../ids';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, LEASE_GUARD, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { type ExportScope, exportBody, parseStoredScope, scopeJson, selectsAdmission } from './scope';
import { ExportTooLarge, MultipartSink, ZipWriter } from './zip-writer';

export { exportBody };

/** Completed export objects are kept 7 days (baseline.md defaults). */
export const EXPORT_TTL_MS = 7 * 24 * 60 * 60 * 1000;
export const EXPORT_SCHEMA_VERSION = 2;
/**
 * Quiet period after an export settles before the sweep treats its objects as final (a ready export's superseded
 * attempts go then, and an export that closed early is swept a second time then). Cloudflare documents a 15-minute
 * maximum wall time for a Queue consumer invocation (https://developers.cloudflare.com/workers/platform/limits/, checked
 * 2026-10-09) and `runJob` runs only from the queue consumer (`index.ts`), so this is twice that bound. It is a margin,
 * not the retention guarantee: an R2 request the runtime had already sent when it ended the invocation has no
 * documented completion bound, so an object can land after any sweep. That is what `EXPORT_RECONCILE_MS` covers.
 */
export const STALE_UPLOAD_GRACE_MS = 30 * 60 * 1000;
/**
 * Every settled export is listed and cleaned again this often, for as long as its row exists, with no cut-off based on how
 * late R2 might complete a request. An object that lands after a sweep (an invalidated or failed export's prefix, or
 * an unpublished attempt of a ready export) is therefore removed within this interval plus the time the scheduled pass
 * takes to reach it, comfortably inside the 24 hours the LLD allows for deleted content. Capacity: a pass visits at most
 * `SWEEP_BATCH` due exports and the pass runs every minute, so 50 x 360 = 18,000 exports fit in one interval; each visit
 * is one prefix listing (plus a delete when something is found) and one D1 update, never a scan of the whole bucket.
 */
export const EXPORT_RECONCILE_MS = 6 * 60 * 60 * 1000;
/** A removal that failed is tried again after this long; other exports are not held up meanwhile. */
const SWEEP_RETRY_MS = 15 * 60 * 1000;
const SWEEP_BATCH = 50;

interface ExportRow {
  id: string;
  snapshot_seq: number;
  state: string;
  manifest_key: string | null;
  object_key: string | null;
  created_at: number;
  expires_at: number;
  scope_json: string | null;
  invalidated_reason: string | null;
  attempt: string | null;
}

type OmissionReason = 'deleted' | 'quarantined' | 'admission_changed' | 'excluded_by_admission' | 'not_in_snapshot';
type Invalidation = 'deleted' | 'quarantined' | 'admission_changed' | 'scope_unknown';

const encoder = new TextEncoder();

/** Ends the job early with a result after the partial upload is aborted. */
class Stop extends Error {
  constructor(readonly result: JobResult) {
    super(result.kind);
  }
}
const jsonl = (rows: unknown[]) => encoder.encode(rows.map((r) => JSON.stringify(r)).join('\n') + (rows.length ? '\n' : ''));
// A name of only dots (`.`, `..`) would be a path-traversal segment inside the ZIP, so it gets the same fallback as an empty name.
const safeName = (name: string) => {
  const cleaned = name.replace(/[\u0000-\u001f\u007f"\\/:*?<>|]/g, '_').slice(0, 120);
  return /^\.*$/.test(cleaned) ? 'attachment' : cleaned;
};
const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

/**
 * SQL for "a source the published attempt contains is deleted, or no longer has the admission the scope asks for"
 * (a normal scope needs `normal`, a quarantine-only scope needs `quarantine`, `all` accepts both). Bind the export ID,
 * the attempt, then the scope's admission twice.
 */
const VIOLATION = `EXISTS (SELECT 1 FROM export_sources es JOIN source_registry r ON r.id = es.source_id WHERE es.export_id = ? AND es.attempt = ? AND (r.state <> 'ready' OR (? <> 'all' AND r.admission <> ?)))`;
/** What a ready export reports when a source no longer has the admission its scope asks for. */
const admissionReason = (scope: ExportScope): Invalidation => (scope.admission === 'normal' ? 'quarantined' : 'admission_changed');

// POST /exports — captures the change-sequence snapshot and the immutable scope, and queues a replay-safe export job.
export async function createExport(env: Env, now: number, body: { scope: ExportScope }) {
  const db = env.CONTROL_DB;
  const id = crypto.randomUUID();
  const jobId = crypto.randomUUID();
  const snapshot = (await db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
  await db.batch([
    db.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at, scope_json) VALUES (?, ?, 'pending', ?, ?, ?)").bind(id, snapshot, now, now + EXPORT_TTL_MS, scopeJson(body.scope)),
    db.prepare("INSERT INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at) VALUES (?, ?, 'export', NULL, NULL, 'pending', ?, ?, ?)")
      .bind(jobId, `export:${id}`, now, JSON.stringify({ exportId: id }), now),
    db.prepare("INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) VALUES (?, ?, 'job', ?, 'pending', ?)").bind(crypto.randomUUID(), `job:${jobId}`, jobId, now),
  ]);
  return { id, state: 'pending', scope: body.scope, snapshotSeq: snapshot, createdAt: iso(now), expiresAt: iso(now + EXPORT_TTL_MS), counts: null, omissions: null, invalidatedReason: null, failureCode: null };
}

async function loadExport(env: Env, id: string): Promise<ExportRow> {
  const row = await env.CONTROL_DB.prepare('SELECT * FROM exports WHERE id = ?').bind(id).first<ExportRow>();
  if (!row) throw notFound();
  return row;
}

/**
 * The state a client may rely on now: the stored state, adjusted by what is true at this moment. An export without a
 * readable scope is unknown and never downloadable. A ready export whose sources were deleted, or no longer have the
 * admission the scope asks for (quarantined under a normal scope, released under a quarantine-only one), is
 * invalidated. A job that ended in failure shows as failed.
 */
async function currentState(env: Env, row: ExportRow, now: number) {
  const scope = parseStoredScope(row.scope_json);
  const base = { scope, failureCode: null as string | null, invalidatedReason: null as Invalidation | null };
  if (scope === null) return { ...base, state: 'invalidated', invalidatedReason: 'scope_unknown' as Invalidation };
  if (row.state === 'invalidated') return { ...base, state: 'invalidated', invalidatedReason: ((row.invalidated_reason as Invalidation | null) ?? 'deleted') };
  if (row.state === 'ready') {
    if (row.expires_at <= now) return { ...base, state: 'expired' };
    const hit = await env.CONTROL_DB.prepare(`SELECT r.state AS state FROM export_sources es JOIN source_registry r ON r.id = es.source_id
      WHERE es.export_id = ? AND es.attempt = ? AND (r.state <> 'ready' OR (? <> 'all' AND r.admission <> ?)) ORDER BY (r.state <> 'ready') DESC LIMIT 1`)
      .bind(row.id, row.attempt, scope.admission, scope.admission)
      .first<{ state: string }>();
    if (hit) return { ...base, state: 'invalidated', invalidatedReason: (hit.state === 'ready' ? admissionReason(scope) : 'deleted') as Invalidation };
    return { ...base, state: 'ready' };
  }
  if (row.state === 'pending' || row.state === 'running' || row.state === 'failed') {
    const job = await env.CONTROL_DB.prepare("SELECT state, error_code FROM jobs WHERE dedupe_key = ?").bind(`export:${row.id}`).first<{ state: string; error_code: string | null }>();
    const failed = row.state === 'failed' || job?.state === 'failed' || job?.state === 'dead';
    if (failed) return { ...base, state: 'failed', failureCode: job?.error_code ?? 'unknown' };
  }
  return { ...base, state: row.state };
}

// GET /exports/{id}
export async function getExport(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const row = await loadExport(env, v.uuid()(id, '$.id'));
  const current = await currentState(env, row, now);
  // A ready export always reports counts and omissions (the shared schema forbids null for ready), so a manifest that is
  // missing or unreadable is an error and never an empty summary.
  const manifest = current.state === 'ready' ? await readManifestSummary(env, row) : null;
  return {
    id: row.id,
    state: current.state,
    scope: current.scope,
    snapshotSeq: row.snapshot_seq,
    createdAt: iso(row.created_at),
    expiresAt: iso(row.expires_at),
    counts: manifest?.counts ?? null,
    omissions: manifest?.omissions ?? null,
    invalidatedReason: current.invalidatedReason,
    failureCode: current.failureCode,
  };
}

const COUNT_KEYS = ['sources', 'attachments', 'concerns', 'rules', 'services'] as const;
const OMISSION_REASONS: readonly string[] = ['deleted', 'quarantined', 'admission_changed', 'excluded_by_admission', 'not_in_snapshot'];
const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0;

/**
 * The counts and omissions of a ready export, from its manifest. The manifest is written by this Worker before the export
 * can become ready, so a ready export whose manifest is gone, unparsable or not in the published shape is a server fault:
 * it is refused with an explicit error (`export_manifest_unavailable` or `export_manifest_invalid`, status 500, content-free)
 * rather than reported with null or empty counts, which the contract forbids and a client could mistake for an empty export.
 */
async function readManifestSummary(env: Env, row: ExportRow): Promise<{ counts: Record<(typeof COUNT_KEYS)[number], number>; omissions: { sourceId: string; reason: string }[] }> {
  const unavailable = () => new HttpError(500, 'export_manifest_unavailable', 'This export is marked ready but its manifest is missing. Create a new export.');
  const invalid = () => new HttpError(500, 'export_manifest_invalid', 'This export is marked ready but its manifest could not be read. Create a new export.');
  if (!row.manifest_key) throw unavailable();
  const object = await env.ARCHIVE_BUCKET.get(row.manifest_key);
  if (!object) throw unavailable();
  let manifest: unknown;
  try {
    manifest = JSON.parse(await object.text());
  } catch {
    throw invalid();
  }
  const { counts, omissions } = (manifest ?? {}) as { counts?: Record<string, unknown>; omissions?: unknown };
  if (typeof counts !== 'object' || counts === null || Array.isArray(counts) || !COUNT_KEYS.every((key) => isCount(counts[key]))) throw invalid();
  if (!Array.isArray(omissions) || !omissions.every((o) => typeof o === 'object' && o !== null && typeof (o as { sourceId?: unknown }).sourceId === 'string' && OMISSION_REASONS.includes((o as { reason?: string }).reason ?? ''))) throw invalid();
  return {
    counts: Object.fromEntries(COUNT_KEYS.map((key) => [key, counts[key]])) as Record<(typeof COUNT_KEYS)[number], number>,
    omissions: omissions.map((o: { sourceId: string; reason: string }) => ({ sourceId: o.sourceId, reason: o.reason })),
  };
}

const INVALIDATION_MESSAGES: Record<Invalidation, [string, string]> = {
  deleted: ['deleted', 'Content in this export was deleted. Create a new export.'],
  quarantined: ['quarantined', 'A source in this export is quarantined now. Create a new export.'],
  admission_changed: ['admission_changed', 'A source in this quarantine-only export was released from quarantine. Create a new export.'],
  scope_unknown: ['scope_unknown', 'This export has no recorded scope. Create a new export.'],
};

// GET /exports/{id}/content — authenticated ZIP stream; deletion, a source whose admission no longer matches the scope, or an unknown scope invalidates it.
export async function getExportContent(request: Request, env: Env, now: number, [id]: string[]): Promise<Response> {
  await authenticate(request, env, now);
  const row = await loadExport(env, v.uuid()(id, '$.id'));
  const current = await currentState(env, row, now);
  if (current.state === 'invalidated') {
    const [code, message] = INVALIDATION_MESSAGES[current.invalidatedReason ?? 'deleted'];
    throw new HttpError(410, code, message);
  }
  if (current.state === 'expired') throw new HttpError(410, 'expired', 'This export expired. Create a new export.');
  if (current.state === 'failed') throw new HttpError(409, 'export_failed', 'This export could not be prepared. Create a new export.');
  if (current.state !== 'ready' || !row.object_key) throw new HttpError(409, 'not_ready', 'The export is still being prepared.', {}, true);
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
 * Fields of a partial concern that are replaced or set to null because their value cannot be tied to the selected
 * sources: the title is rebuilt from a selected source's subject; the rest is nulled (nothing in a concern records which
 * email a status, importance, date, wait or service came from). Kept: id, revision, the owner's read and acknowledge
 * times (owner actions, no mail content) and the links to selected sources. The key set is the same as a full concern's.
 */
const PARTIAL_CONCERN_WITHHELD = ['title', 'status', 'importance', 'criticalBasis', 'serviceId', 'resolvedAt', 'snoozeUntil', 'waiting', 'due', 'updatedAt'];

interface ConcernProjection {
  id: string;
  revision: number;
  readAt?: string | null;
  acknowledgedAt?: string | null;
  sourceIds?: string[];
}

interface Candidate extends RegistryRow {
  ready_seq: number;
  staged_key: string | null;
  snapshot_admission: string;
}

const CHUNK = 90;
const marks = (n: number) => Array.from({ length: n }, () => '?').join(',');

/**
 * The sources a scope selects at the snapshot, by their state then: ready at or before it, not tombstoned, with the
 * admission they had at the snapshot (from the latest change record, so releasing or quarantining a source later does
 * not move it in or out). Registry columns only; address filters are applied from the immutable archive row.
 */
async function selectCandidates(db: D1Database, scope: ExportScope, snapshot: number): Promise<Candidate[]> {
  const where = ['r.ready_seq IS NOT NULL', 'r.ready_seq <= ?'];
  const params: unknown[] = [snapshot];
  if (scope.kind === 'filter') {
    if (scope.filter.from) (where.push('r.received_at >= ?'), params.push(Date.parse(scope.filter.from)));
    if (scope.filter.to) (where.push('r.received_at < ?'), params.push(Date.parse(scope.filter.to)));
  }
  const select = `SELECT ${REGISTRY_COLUMNS}, r.ready_seq, r.staged_key, COALESCE(json_extract(c.projection_json, '$.admission'), 'quarantine') AS snapshot_admission
    FROM ${REGISTRY_FROM} JOIN changes c ON c.entity_type = 'source' AND c.entity_id = r.id AND c.deleted = 0
      AND c.seq = (SELECT MAX(n.seq) FROM changes n WHERE n.entity_type = 'source' AND n.entity_id = r.id AND n.seq <= ?)
    WHERE ${where.join(' AND ')}`;
  if (scope.kind !== 'sources') {
    return (await db.prepare(`${select} ORDER BY r.received_at, r.id`).bind(snapshot, ...params).all<Candidate>()).results;
  }
  const found: Candidate[] = [];
  for (let i = 0; i < scope.sourceIds.length; i += CHUNK) {
    const ids = scope.sourceIds.slice(i, i + CHUNK);
    found.push(...(await db.prepare(`${select} AND r.id IN (${marks(ids.length)})`).bind(snapshot, ...params, ...ids).all<Candidate>()).results);
  }
  return found.sort((a, b) => a.received_at - b.received_at || (a.id < b.id ? -1 : 1));
}

/** Exact address criteria of a filter scope, evaluated on the immutable archive envelope like GET /search does. */
function matchesAddresses(scope: ExportScope, envelopeJson: string): boolean {
  if (scope.kind !== 'filter') return true;
  const envelope = JSON.parse(envelopeJson) as { from?: string[]; to?: string[] };
  const has = (list: string[] | undefined, wanted: string | undefined) => wanted === undefined || (list ?? []).some((entry) => entry.toLowerCase() === wanted);
  return has(envelope.from, scope.filter.sender) && has(envelope.to, scope.filter.recipient);
}

/**
 * Builds the export ZIP (lld.md#exports): sources.jsonl, attachments.jsonl, concerns.jsonl, rules.jsonl,
 * services.jsonl, files/... and manifest.json with scope, hashes and omissions. Every attempt writes only under its
 * own `exports/{id}/{attempt}/` (the attempt is the job's lease token), so a stale attempt can never overwrite the
 * published ZIP; only the attempt that still holds the lease records its membership and publishes its keys. The sources are the scope's selection at
 * the snapshot; deletion and admission are checked again as each is written, so a later deletion, or a quarantine
 * that the scope excludes, is listed as omitted and never resurrected. Only the selected sources' records, files and
 * relationships are written: concerns keep just their selected links and lose any text that may come from another
 * source, and the owner's rules and services are included only for a whole-archive scope. Secrets, sessions and
 * signing material are never read.
 */
export async function exportJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  const exportId = (job.input as { exportId?: unknown }).exportId;
  if (typeof exportId !== 'string') return { kind: 'fail', errorCode: 'invalid_input' };
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT * FROM exports WHERE id = ?').bind(exportId).first<ExportRow>();
  if (!row || !['pending', 'running'].includes(row.state)) return { kind: 'complete', output: { skipped: row?.state ?? 'missing' } };
  const scope = parseStoredScope(row.scope_json);
  if (scope === null) {
    // No scope was recorded: it is never read as a wider one.
    return {
      kind: 'fail',
      errorCode: 'scope_unknown',
      statements: [db.prepare(`UPDATE exports SET state = 'invalidated', invalidated_reason = 'scope_unknown' WHERE id = ? AND state IN ('pending', 'running') AND ${CLAIM_GUARD}`).bind(exportId, ...guardParams(job))],
    };
  }
  const [guardId, guardToken] = guardParams(job);
  const attempt = job.leaseToken;
  // 'lease_lost': a newer claim replaced this one. 'closed': the export was invalidated or settled meanwhile. Either way
  // this attempt must not start or finish an object it cannot publish.
  const standing = async (): Promise<'open' | 'lease_lost' | 'closed'> => {
    const held = await db.prepare("SELECT 1 AS held FROM jobs WHERE id = ? AND lease_token = ? AND state = 'running'").bind(guardId, guardToken).first();
    if (!held) return 'lease_lost';
    const open = await db.prepare("SELECT 1 AS open FROM exports WHERE id = ? AND state IN ('pending', 'running')").bind(exportId).first();
    return open ? 'open' : 'closed';
  };
  const stopUnless = async () => {
    const now = await standing();
    if (now === 'lease_lost') throw new Stop({ kind: 'retry', errorCode: 'lease_lost' });
    if (now === 'closed') throw new Stop({ kind: 'complete', output: { skipped: 'closed' } });
  };
  await db.prepare("UPDATE exports SET state = 'running' WHERE id = ? AND state = 'pending'").bind(exportId).run();
  // Membership left by an earlier attempt of this export; fenced, so a stale attempt cannot remove the winner's.
  await db.prepare(`DELETE FROM export_sources WHERE export_id = ? AND attempt <> ? AND ${LEASE_GUARD}`).bind(exportId, attempt, guardId, guardToken).run();

  const objectKey = `exports/${exportId}/${attempt}/export.zip`;
  const manifestKey = `exports/${exportId}/${attempt}/manifest.json`;
  // Checked immediately before the first object exists: after this an attempt is always accounted for by the sweep.
  const early = await standing();
  if (early === 'lease_lost') return { kind: 'retry', errorCode: 'lease_lost' };
  if (early === 'closed') return { kind: 'complete', output: { skipped: 'closed' } };
  const upload = await env.ARCHIVE_BUCKET.createMultipartUpload(objectKey, { httpMetadata: { contentType: 'application/zip' } });
  // R2 does not list the parts of an unfinished multipart upload, so the prefix sweep cannot find them: the upload ID is
  // recorded durably, and the sweep (or this attempt's own cleanup) aborts it. This narrows, and cannot close, the window
  // between R2 creating the upload and D1 holding the record: if the response to createMultipartUpload is lost, or the
  // invocation ends between the two calls, nothing here knows the upload ID. That residue is bounded only by the bucket's
  // lifecycle rule for incomplete multipart uploads (a deployment gate, lld.md#exports). If the record cannot be written
  // the upload is aborted at once, best effort, before the failure is raised.
  try {
    await db.prepare('INSERT INTO export_uploads(export_id, attempt, upload_id, object_key, created_at) VALUES (?, ?, ?, ?, ?)').bind(exportId, attempt, upload.uploadId, objectKey, now).run();
  } catch (error) {
    await upload.abort().catch(() => undefined);
    throw error;
  }
  const sink = new MultipartSink(upload);
  const zip = new ZipWriter(sink.write, new Date(row.created_at));
  const files: { path: string; sha256: string; bytes: number }[] = [];
  const add = async (path: string, data: Uint8Array) => {
    await zip.add(path, data);
    files.push({ path, sha256: await sha256Hex(data), bytes: data.length });
  };
  const ownerConfig = scope.kind === 'archive';

  try {
    const everything = await selectCandidates(db, scope, row.snapshot_seq);
    const omissions: { sourceId: string; reason: OmissionReason }[] = [];
    const candidates = everything.filter((c) => {
      if (selectsAdmission(scope, c.snapshot_admission)) return true;
      // Named sources the admission excludes are accounted for; an archive or filter scope simply does not select them.
      if (scope.kind === 'sources') omissions.push({ sourceId: c.id, reason: 'excluded_by_admission' });
      return false;
    });
    if (scope.kind === 'sources') {
      const seen = new Set(everything.map((c) => c.id));
      for (const sourceId of scope.sourceIds) if (!seen.has(sourceId)) omissions.push({ sourceId, reason: 'not_in_snapshot' });
    }
    const sources: unknown[] = [];
    const subjects = new Map<string, string>();
    const attachmentsOut: unknown[] = [];
    for (const candidate of candidates) {
      const current = await db.prepare('SELECT state, admission FROM source_registry WHERE id = ?').bind(candidate.id).first<{ state: string; admission: string }>();
      if (current?.state !== 'ready') {
        omissions.push({ sourceId: candidate.id, reason: 'deleted' });
        continue;
      }
      if (scope.admission === 'normal' && current.admission === 'quarantine') {
        omissions.push({ sourceId: candidate.id, reason: 'quarantined' });
        continue;
      }
      if (scope.admission === 'quarantine' && current.admission !== 'quarantine') {
        omissions.push({ sourceId: candidate.id, reason: 'admission_changed' });
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
      if (!matchesAddresses(scope, stored.envelope_json)) continue;
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
      subjects.set(candidate.id, stored.subject);
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
    const includedIds = (sources as { id: string }[]).map((s) => s.id);
    const included = new Set(includedIds);
    // A concern keeps only links to selected sources. When it also has others, nothing on it records which email a
    // title, status, importance, date, wait or service came from, so a partial concern is rebuilt from an allowlist:
    // the title from the first selected source, everything else that could carry another source's content is null.
    // `partial` and `withheld` say so; the record is not the original concern and is not importable as one.
    let partialConcerns = 0;
    const concerns = ((await snapshotProjections(db, 'concern', row.snapshot_seq)) as ConcernProjection[]).flatMap((c) => {
      const all = c.sourceIds ?? [];
      const kept = all.filter((s) => included.has(s));
      if (kept.length === 0) return [];
      if (kept.length === all.length) return [{ ...c, sourceIds: kept, partial: false, withheld: [] as string[] }];
      partialConcerns += 1;
      const first = includedIds.find((id) => kept.includes(id)) as string;
      return [
        {
          id: c.id,
          revision: c.revision,
          title: subjects.get(first)?.trim().slice(0, 200) || '(no subject)',
          status: null,
          importance: null,
          criticalBasis: null,
          serviceId: null,
          readAt: c.readAt ?? null,
          acknowledgedAt: c.acknowledgedAt ?? null,
          resolvedAt: null,
          snoozeUntil: null,
          waiting: null,
          due: null,
          sourceIds: kept,
          updatedAt: null,
          partial: true,
          withheld: PARTIAL_CONCERN_WITHHELD,
        },
      ];
    });
    const rules = !ownerConfig
      ? []
      : (await db.prepare('SELECT id, revision, origin, enabled, predicate_json, effect_json, created_at FROM rules ORDER BY created_at, id').all<{
          id: string;
          revision: number;
          origin: string;
          enabled: number;
          predicate_json: string;
          effect_json: string;
          created_at: number;
        }>()).results.map((r) => ({ id: r.id, revision: r.revision, origin: r.origin, enabled: r.enabled === 1, predicate: JSON.parse(r.predicate_json) as unknown, effects: JSON.parse(r.effect_json) as unknown, createdAt: iso(r.created_at) }));
    const services = !ownerConfig
      ? []
      : (await db.prepare('SELECT id, revision, name, aliases_json, created_at FROM service_catalog ORDER BY name, id').all<{ id: string; revision: number; name: string; aliases_json: string; created_at: number }>()).results.map((s) => ({
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
      scope,
      counts: { sources: sources.length, attachments: attachmentsOut.length, concerns: concerns.length, rules: rules.length, services: services.length },
      sections: { rules: ownerConfig ? 'included' : 'omitted_by_scope', services: ownerConfig ? 'included' : 'omitted_by_scope', partialConcerns },
      omissions,
      files,
    };
    const manifestBytes = encoder.encode(JSON.stringify(manifest, null, 2));
    await zip.add('manifest.json', manifestBytes);
    await zip.finish();
    // A superseded attempt stops before it creates an object it could never publish.
    await stopUnless();
    await sink.complete();
    // Completed: nothing is left to abort. If the record stays, the sweep removes it without touching the published ZIP.
    await db.prepare('DELETE FROM export_uploads WHERE export_id = ? AND attempt = ?').bind(exportId, attempt).run().catch(() => undefined);
    await env.ARCHIVE_BUCKET.put(manifestKey, manifestBytes, { httpMetadata: { contentType: 'application/json' } });
    // Membership is written before the export can become ready: deletion and the download check read it. Each insert
    // is fenced by the lease (84 binds at most), so a stale attempt records nothing.
    for (let i = 0; i < includedIds.length; i += 800) {
      const statements: D1PreparedStatement[] = [];
      for (let j = i; j < Math.min(i + 800, includedIds.length); j += 80) {
        const part = includedIds.slice(j, Math.min(j + 80, includedIds.length, i + 800));
        statements.push(
          db
            .prepare(`INSERT OR IGNORE INTO export_sources(export_id, attempt, source_id) SELECT ?, ?, column1 FROM (VALUES ${part.map(() => '(?)').join(', ')}) WHERE ${LEASE_GUARD}`)
            .bind(exportId, attempt, ...part, guardId, guardToken),
        );
      }
      await db.batch(statements);
    }
    await stopUnless();
  } catch (error) {
    // Nothing of this attempt is published, so its objects are removed now; if that fails the sweep removes them, and an
    // upload that could not be aborted stays recorded for the sweep to abort.
    if (!(await abortRecordedUpload(db, upload, exportId, attempt))) console.warn(JSON.stringify({ event: 'export_upload_abort_deferred' }));
    await env.ARCHIVE_BUCKET.delete([objectKey, manifestKey]).catch(() => undefined);
    await db.prepare('DELETE FROM export_sources WHERE export_id = ? AND attempt = ?').bind(exportId, attempt).run().catch(() => undefined);
    if (error instanceof Stop) return error.result;
    if (error instanceof ExportTooLarge) {
      return { kind: 'fail', errorCode: 'export_too_large', statements: [db.prepare(`UPDATE exports SET state = 'failed' WHERE id = ? AND ${CLAIM_GUARD}`).bind(exportId, ...guardParams(job))] };
    }
    throw error;
  }
  const normalAdmission = scope.admission;
  // Ready only if every included source is still not deleted and still has the admission the scope asks for, checked in
  // the same transaction, and only for the attempt that holds the claim. A deletion that already invalidated this export
  // keeps it invalid; its attempt objects are removed by the sweep (no key is recorded for an export that is not ready).
  return {
    kind: 'complete',
    output: { exportId },
    statements: [
      db
        .prepare(`UPDATE exports SET state = 'ready', object_key = ?, manifest_key = ?, attempt = ?, settled_at = ? WHERE id = ? AND state = 'running' AND NOT ${VIOLATION} AND ${CLAIM_GUARD}`)
        .bind(objectKey, manifestKey, attempt, now, exportId, exportId, attempt, normalAdmission, normalAdmission, guardId, guardToken),
      db
        .prepare(
          `UPDATE exports SET state = 'invalidated', settled_at = ?, invalidated_reason = CASE WHEN EXISTS (SELECT 1 FROM export_sources es JOIN source_registry r ON r.id = es.source_id WHERE es.export_id = ? AND es.attempt = ? AND r.state <> 'ready') THEN 'deleted' ELSE ? END
           WHERE id = ? AND state = 'running' AND ${VIOLATION} AND ${CLAIM_GUARD}`,
        )
        .bind(now, exportId, attempt, admissionReason(scope), exportId, exportId, attempt, normalAdmission, normalAdmission, guardId, guardToken),
      // Membership of every other attempt is dropped with the publication, which the claim guard makes the winner's alone.
      db.prepare(`DELETE FROM export_sources WHERE export_id = ? AND attempt <> ? AND ${CLAIM_GUARD}`).bind(exportId, attempt, guardId, guardToken),
    ],
  };
}

/** R2 reports an upload that is gone (completed, aborted or expired) as NoSuchUpload, code 10024 (https://developers.cloudflare.com/r2/api/error-codes/, checked 2026-10-09). */
const isNoSuchUpload = (error: unknown) => {
  const { code, message } = (error ?? {}) as { code?: unknown; message?: unknown };
  return code === 10024 || (typeof message === 'string' && (/\(10024\)/.test(message) || /NoSuchUpload/i.test(message)));
};

/**
 * Aborts one recorded multipart upload and forgets it. An upload that no longer exists counts as aborted. Returns false,
 * leaving the record in place for the next sweep, when R2 could not be reached or refused.
 */
async function abortRecordedUpload(db: D1Database, upload: Pick<R2MultipartUpload, 'abort' | 'uploadId'>, exportId: string, attempt: string): Promise<boolean> {
  try {
    await upload.abort();
  } catch (error) {
    if (!isNoSuchUpload(error)) return false;
  }
  await db.prepare('DELETE FROM export_uploads WHERE export_id = ? AND attempt = ? AND upload_id = ?').bind(exportId, attempt, upload.uploadId).run().catch(() => undefined);
  return true;
}

interface SweepRow {
  id: string;
  state: string;
  object_key: string | null;
  manifest_key: string | null;
  scope_json: string | null;
  expires_at: number;
  settled_at: number | null;
  attempt: string | null;
  job_over: number;
}

/** The export's job can never claim again, so no new attempt can start (the open check in exportJob closes the rest). */
const JOB_OVER = "EXISTS (SELECT 1 FROM jobs j WHERE j.dedupe_key = 'export:' || e.id AND j.state IN ('failed', 'dead', 'cancelled'))";
/** Every object of the export goes: it is invalidated, failed, expired, unscoped or its job is over. */
const REMOVE_ALL = `(e.state IN ('invalidated', 'expired', 'failed') OR e.scope_json IS NULL OR (e.state = 'ready' AND e.expires_at <= ?1) OR (e.state IN ('pending', 'running') AND ${JOB_OVER}))`;

/**
 * Scheduled sweep of export objects, by prefix, so an object is found whether or not a key was ever recorded, and of
 * unfinished multipart uploads, which a listing cannot show and which are therefore recorded in `export_uploads` and
 * aborted here (every recorded upload of a going-away export, and of a ready export every one but the published attempt's):
 * - an invalidated, failed, expired, unscoped or abandoned export loses everything under `exports/{id}/` and its
 *   recorded keys (deleted content is gone within the 24 hours the LLD promises, subject to the schedule);
 * - a ready export keeps only the published ZIP and manifest, once the grace has passed, so attempts that lost their
 *   lease (and any object one landed late) do not stay for the seven days.
 * Neither ever stops being scheduled: each visit sets `cleanup_next_at` to now + `EXPORT_RECONCILE_MS` (a ready export is
 * also due at its expiry, so expiring is not delayed by the interval; an export that closed early is visited a second
 * time when the grace has passed). A removal that fails (or leaves the row unrecorded) is retried after
 * `SWEEP_RETRY_MS` and does not hold up other exports. Due work is ordered by when it became due (`cleanup_next_at`, else
 * when the export settled), so a newly closed export neither jumps ahead of overdue visits nor waits behind visits that
 * are not yet due; 50 exports per pass. Exports being built are never swept. Returns how many exports had objects removed.
 */
export async function expireExports(env: Pick<Env, 'CONTROL_DB' | 'ARCHIVE_BUCKET'>, now = Date.now()): Promise<{ removed: number }> {
  const db = env.CONTROL_DB;
  const { results } = await db
    .prepare(
      `SELECT e.id, e.state, e.object_key, e.manifest_key, e.scope_json, e.expires_at, e.settled_at, e.attempt, ${JOB_OVER} AS job_over FROM exports e
       WHERE (e.cleanup_next_at IS NULL OR e.cleanup_next_at <= ?1)
         AND (${REMOVE_ALL}
           OR (e.state = 'ready' AND e.expires_at > ?1 AND e.settled_at IS NOT NULL AND e.settled_at + ?2 <= ?1))
       ORDER BY COALESCE(e.cleanup_next_at, e.settled_at, e.created_at), e.created_at, e.id LIMIT ?3`,
    )
    .bind(now, STALE_UPLOAD_GRACE_MS, SWEEP_BATCH)
    .all<SweepRow>();
  let removed = 0;
  let failed = 0;
  for (const row of results) {
    try {
      if (await sweepExport(env, row, now)) removed += 1;
    } catch {
      failed += 1;
      // Later, and behind everything else that is due; the content-free event is the only trace.
      await db.prepare('UPDATE exports SET cleanup_next_at = ? WHERE id = ?').bind(now + SWEEP_RETRY_MS, row.id).run().catch(() => undefined);
    }
  }
  if (failed > 0) console.warn(JSON.stringify({ event: 'export_cleanup_failed', failed }));
  return { removed };
}

async function sweepExport(env: Pick<Env, 'CONTROL_DB' | 'ARCHIVE_BUCKET'>, row: SweepRow, now: number): Promise<boolean> {
  const db = env.CONTROL_DB;
  const everything = row.scope_json === null || ['invalidated', 'expired', 'failed'].includes(row.state) || (row.state === 'ready' && row.expires_at <= now) || (['pending', 'running'].includes(row.state) && row.job_over === 1);
  const published = new Set(everything ? [] : [row.object_key, row.manifest_key].filter((key): key is string => key !== null));
  const doomed = new Set<string>(everything ? [row.object_key, row.manifest_key].filter((key): key is string => key !== null) : []);
  // Unfinished multipart uploads first: their parts do not appear in a listing. The published attempt's upload is finished
  // (the export became ready only after it completed), so its record is dropped without an abort; a ready export's other
  // attempts, and every attempt of an export that is going away, are aborted. An abort that fails keeps its record, the
  // rest of the sweep still runs, and the failure is raised at the end so the export is retried in 15 minutes.
  const { results: recorded } = await db.prepare('SELECT attempt, upload_id, object_key FROM export_uploads WHERE export_id = ?').bind(row.id).all<{ attempt: string; upload_id: string; object_key: string }>();
  let aborted = 0;
  let stuck = 0;
  for (const upload of recorded) {
    if (!everything && upload.attempt === row.attempt) {
      await db.prepare('DELETE FROM export_uploads WHERE export_id = ? AND attempt = ? AND upload_id = ?').bind(row.id, upload.attempt, upload.upload_id).run();
    } else if (await abortRecordedUpload(db, env.ARCHIVE_BUCKET.resumeMultipartUpload(upload.object_key, upload.upload_id), row.id, upload.attempt)) {
      aborted += 1;
    } else {
      stuck += 1;
    }
  }
  let cursor: string | undefined;
  do {
    const page: R2Objects = await env.ARCHIVE_BUCKET.list({ prefix: `exports/${row.id}/`, limit: 1000, ...(cursor ? { cursor } : {}) });
    for (const object of page.objects) if (!published.has(object.key)) doomed.add(object.key);
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  const keys = [...doomed];
  for (let i = 0; i < keys.length; i += 1000) await env.ARCHIVE_BUCKET.delete(keys.slice(i, i + 1000));

  const settled = row.settled_at ?? now;
  const quiet = settled + STALE_UPLOAD_GRACE_MS;
  if (everything) {
    // Membership matters only while an export can be downloaded; a build still holding its lease may add a row until it ends.
    await db.batch([
      db.prepare('DELETE FROM export_sources WHERE export_id = ?').bind(row.id),
      db
        .prepare(
          `UPDATE exports SET object_key = NULL, manifest_key = NULL, settled_at = ?, cleanup_next_at = ?,
             state = CASE WHEN state IN ('invalidated', 'failed') THEN state WHEN state IN ('pending', 'running') AND ? = 1 THEN 'failed' ELSE 'expired' END WHERE id = ?`,
        )
        .bind(settled, now >= quiet ? now + EXPORT_RECONCILE_MS : quiet, row.job_over, row.id),
    ]);
  } else {
    // Due again at the interval, or at expiry if that comes first, when the whole prefix goes. If a deletion invalidated the
    // export meanwhile this changes nothing and the deletion's own reset (cleanup_next_at = NULL) makes it due at once.
    await db.prepare("UPDATE exports SET cleanup_next_at = ? WHERE id = ? AND state = 'ready'").bind(Math.min(now + EXPORT_RECONCILE_MS, row.expires_at), row.id).run();
  }
  if (stuck > 0) throw new Error('multipart_abort_failed');
  return keys.length > 0 || aborted > 0;
}
