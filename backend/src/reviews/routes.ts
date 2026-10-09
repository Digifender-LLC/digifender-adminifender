import { type AuthContext, authenticate } from '../auth/sessions';
import { assertion, isAssertionFailure } from '../db/assert';
import { type DeletionPlan, archiveDeletionStatements, planArchiveDeletion } from '../deletion/archive';
import { appendLedger } from '../deletion/ledger';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { canonicalJson } from '../http/idempotency';
import { type Validator, v } from '../http/validate';
import { sha256Hex } from '../ids';

/** Review expiry (baseline.md defaults): 10 minutes, bound to exact parameters and current revisions. */
export const REVIEW_TTL_MS = 10 * 60 * 1000;
export const MAX_DELETE_SOURCES = 50;

type Effect = 'archive.delete' | 'device.revoke';
type Params = { sourceIds: string[] } | { deviceId: string };

interface Plan {
  preconditions: Record<string, Record<string, number>>;
  preview: Record<string, unknown>;
  deletion?: DeletionPlan;
}

const sourceIdList: Validator<string[]> = (value, path) => {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_DELETE_SOURCES) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'expected_list' });
  const ids = value.map((item, i) => v.uuid()(item, `${path}[${i}]`));
  if (new Set(ids).size !== ids.length) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'duplicate' });
  return [...ids].sort();
};

/** `{effect, params}` for the native review kinds; external effects arrive with their adapters (P3). */
export const reviewBody: Validator<{ effect: Effect; params: Params }> = (value, path) => {
  const outer = v.object({ effect: v.oneOf(['archive.delete', 'device.revoke'] as const), params: v.record() })(value, path);
  const params = outer.effect === 'archive.delete' ? v.object({ sourceIds: sourceIdList })(outer.params, `${path}.params`) : v.object({ deviceId: v.uuid() })(outer.params, `${path}.params`);
  return { effect: outer.effect, params };
};

async function planDeviceRevoke(env: Env, deviceId: string, currentDeviceId: string): Promise<Plan> {
  const device = await env.CONTROL_DB.prepare('SELECT id, name, revision, revoked_at FROM devices WHERE id = ?').bind(deviceId).first<{ id: string; name: string; revision: number; revoked_at: number | null }>();
  if (!device || device.revoked_at !== null) throw notFound();
  const sessions = await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM sessions WHERE device_id = ? AND revoked_at IS NULL').bind(deviceId).first<{ n: number }>();
  return {
    preconditions: { devices: { [device.id]: device.revision } },
    preview: { device: { id: device.id, name: device.name }, current: device.id === currentDeviceId, sessionsRevoked: sessions?.n ?? 0, expectedCount: 1 },
  };
}

async function plan(env: Env, effect: Effect, params: Params, currentDeviceId: string): Promise<Plan> {
  if (effect === 'archive.delete') {
    const deletion = await planArchiveDeletion(env, (params as { sourceIds: string[] }).sourceIds);
    return { preconditions: deletion.preconditions, preview: deletion.preview, deletion };
  }
  return planDeviceRevoke(env, (params as { deviceId: string }).deviceId, currentDeviceId);
}

const digestOf = (effect: Effect, params: Params, preconditions: Plan['preconditions']) => sha256Hex(canonicalJson({ effect, params, preconditions }));

// POST /reviews — the backend reads the current authorized targets and binds the review to them.
export async function createReview(env: Env, now: number, body: ReturnType<typeof reviewBody>, auth: AuthContext) {
  const planned = await plan(env, body.effect, body.params, auth.deviceId);
  const id = crypto.randomUUID();
  const paramsDigest = await digestOf(body.effect, body.params, planned.preconditions);
  const expiresAt = now + REVIEW_TTL_MS;
  await env.CONTROL_DB.prepare('INSERT INTO action_reviews(id, kind, params_json, digest, preconditions_json, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .bind(id, body.effect, JSON.stringify(body.params), paramsDigest, JSON.stringify(planned.preconditions), expiresAt, now)
    .run();
  return { reviewId: id, effect: body.effect, params: body.params, paramsDigest, preconditions: planned.preconditions, preview: planned.preview, expiresAt: new Date(expiresAt).toISOString(), requiresRecentAuth: true };
}

export const confirmBody = v.object({ paramsDigest: v.string({ max: 64, pattern: /^[0-9a-f]{64}$/ }) });

/**
 * POST /reviews/{id}/confirm — recent passkey session required. Claims the review once and records the operation in
 * the same control transaction as the domain change. Archive deletion first appends the independent ledger record.
 */
export async function confirmReview(env: Env, now: number, body: ReturnType<typeof confirmBody>, auth: AuthContext, id: string) {
  const db = env.CONTROL_DB;
  const review = await db.prepare('SELECT id, kind, params_json, digest, preconditions_json, expires_at, consumed_operation_id FROM action_reviews WHERE id = ?').bind(id).first<{
    id: string;
    kind: Effect;
    params_json: string;
    digest: string;
    preconditions_json: string;
    expires_at: number;
    consumed_operation_id: string | null;
  }>();
  if (!review) throw notFound();
  if (review.consumed_operation_id) throw new HttpError(409, 'review_consumed', 'This review was already used.', { operationId: review.consumed_operation_id });
  if (review.expires_at <= now) throw new HttpError(410, 'review_expired', 'This review expired. Review the change again.');
  if (body.paramsDigest !== review.digest) throw new HttpError(409, 'review_mismatch', 'The confirmed change differs from the review.');
  const params = JSON.parse(review.params_json) as Params;
  // Targets must still be exactly what was reviewed.
  let current: Plan;
  try {
    current = await plan(env, review.kind, params, auth.deviceId);
  } catch (error) {
    if (error instanceof HttpError && error.status === 404) throw new HttpError(409, 'stale_review', 'Something in this review changed. Review it again.');
    throw error;
  }
  if (canonicalJson(current.preconditions) !== canonicalJson(JSON.parse(review.preconditions_json))) throw new HttpError(409, 'stale_review', 'Something in this review changed. Review it again.');

  const operationId = crypto.randomUUID();
  let receipt: Record<string, unknown> = {};
  const statements: D1PreparedStatement[] = [
    assertion(db, 'EXISTS (SELECT 1 FROM action_reviews WHERE id = ? AND consumed_operation_id IS NULL AND expires_at > ?)', id, now),
    db.prepare('UPDATE action_reviews SET consumed_operation_id = ? WHERE id = ? AND consumed_operation_id IS NULL').bind(operationId, id),
  ];
  if (review.kind === 'archive.delete') {
    const deletion = current.deletion as DeletionPlan;
    let head;
    try {
      head = await appendLedger(env, { operationId, sourceIds: deletion.sourceIds, deletedAt: now });
    } catch {
      // Not falsely complete: nothing is hidden until the suppression record exists.
      throw new HttpError(503, 'ledger_unavailable', 'Deletion could not be recorded yet. Try again.', {}, true);
    }
    receipt = { ledgerSeq: head.seq, ledgerRecordHash: head.recordHash, sourcesDeleted: deletion.sourceIds.length };
    statements.push(...(await archiveDeletionStatements(env, deletion, operationId, now)));
  } else {
    const deviceId = (params as { deviceId: string }).deviceId;
    const revision = current.preconditions.devices?.[deviceId] as number;
    statements.push(
      assertion(db, 'EXISTS (SELECT 1 FROM devices WHERE id = ? AND revision = ? AND revoked_at IS NULL)', deviceId, revision),
      db.prepare('UPDATE devices SET revoked_at = ?, revision = revision + 1, fcm_token_cipher = NULL WHERE id = ?').bind(now, deviceId),
      db.prepare('UPDATE sessions SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL').bind(now, deviceId),
    );
    receipt = { deviceRevoked: deviceId };
  }
  statements.push(
    db.prepare("INSERT INTO operations(id, review_id, state, intent_json, receipt_json, created_at, dispatched_at, settled_at) VALUES (?, ?, 'succeeded', ?, ?, ?, ?, ?)")
      .bind(operationId, id, canonicalJson({ effect: review.kind, params }), JSON.stringify(receipt), now, now, now),
  );
  try {
    await db.batch(statements);
  } catch (error) {
    if (isAssertionFailure(error)) throw new HttpError(409, 'stale_review', 'Something in this review changed. Review it again.');
    throw error;
  }
  return { operationId, state: 'succeeded', receipt };
}

// GET /reviews/{id} — content-free recovery readback. Read-only: it creates and confirms nothing and needs no recent
// passkey session. It never returns parameters, previews or excerpts, so it is safe after the targets are deleted.
export async function getReview(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const row = await env.CONTROL_DB.prepare(
    'SELECT r.id, r.kind, r.digest, r.expires_at, r.created_at, r.consumed_operation_id, o.state AS operation_state FROM action_reviews r LEFT JOIN operations o ON o.id = r.consumed_operation_id WHERE r.id = ?',
  )
    .bind(v.uuid()(id, '$.id'))
    .first<{ id: string; kind: Effect; digest: string; expires_at: number; created_at: number; consumed_operation_id: string | null; operation_state: string | null }>();
  if (!row) throw notFound();
  return {
    reviewId: row.id,
    effect: row.kind,
    paramsDigest: row.digest,
    state: row.consumed_operation_id ? 'consumed' : row.expires_at <= now ? 'expired' : 'open',
    createdAt: new Date(row.created_at).toISOString(),
    expiresAt: new Date(row.expires_at).toISOString(),
    requiresRecentAuth: true,
    consumedOperationId: row.consumed_operation_id,
    operationState: row.consumed_operation_id ? (row.operation_state ?? 'uncertain') : null,
  };
}

type CleanupState = 'pending' | 'partial' | 'complete' | 'failed' | 'unknown';

/**
 * Cleanup progress of an archive deletion, derived from the deletion ledger rows written by this operation for the
 * sources in its intent. The receipt only means hidden, tombstoned and recorded; removal of archive rows and objects
 * is asynchronous. Sources the ledger does not account for under this operation make the answer `unknown`, and so
 * does a source whose ledger row contradicts its cleanup job (the job finished, for example by finding the source
 * already deleted, yet the row never became `completed`): that is counted as `unrecorded`, because the ledger cannot
 * be believed for it. `live_archive` covers the live archive rows and the source's own R2 prefixes; it excludes
 * export objects (they expire and are swept on their own schedule, see `expireExports`) and backups. A job waiting
 * to retry is reported as `pending` or `partial`, not as a failure.
 */
async function archiveCleanup(env: Env, operationId: string, sourceIds: string[]) {
  // An intent without sources is not something this module writes; claim nothing about it.
  if (!Array.isArray(sourceIds) || sourceIds.length === 0) return { scope: 'live_archive' as const, state: 'unknown' as CleanupState, total: 0, completed: 0, failed: 0, unrecorded: 0 };
  const { results } = await env.CONTROL_DB.prepare(
    `SELECT l.source_id, l.state, (SELECT j.state FROM jobs j WHERE j.dedupe_key = 'cleanup:' || l.source_id) AS job_state FROM deletion_ledger l
     WHERE json_extract(l.targets_json, '$.operationId') = ? AND l.source_id IN (${sourceIds.map(() => '?').join(',')})`,
  )
    .bind(operationId, ...sourceIds)
    .all<{ source_id: string; state: string; job_state: string | null }>();
  const total = sourceIds.length;
  const recorded = new Set(results.map((r) => r.source_id));
  const missing = sourceIds.filter((s) => !recorded.has(s)).length;
  // A finished cleanup job whose ledger row is still pending: the two disagree, so no state is claimed for the source.
  const contradicted = results.filter((r) => r.state !== 'completed' && r.job_state === 'complete').length;
  const unrecorded = missing + contradicted;
  const completed = results.filter((r) => r.state === 'completed').length;
  // Not completed and either out of retries or without any cleanup job to run: needs attention, never silently pending.
  const failed = results.filter((r) => r.state !== 'completed' && (r.job_state === 'failed' || r.job_state === 'dead' || r.job_state === 'cancelled' || r.job_state === null)).length;
  let state: CleanupState;
  if (unrecorded > 0) state = 'unknown';
  else if (failed > 0) state = 'failed';
  else if (completed === total) state = 'complete';
  else state = completed === 0 ? 'pending' : 'partial';
  return { scope: 'live_archive' as const, state, total, completed, failed, unrecorded };
}

// GET /operations/{id}
export async function getOperation(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const row = await env.CONTROL_DB.prepare('SELECT id, review_id, state, intent_json, receipt_json, error_code, created_at, settled_at FROM operations WHERE id = ?').bind(v.uuid()(id, '$.id')).first<{
    id: string;
    review_id: string;
    state: string;
    intent_json: string;
    receipt_json: string | null;
    error_code: string | null;
    created_at: number;
    settled_at: number | null;
  }>();
  if (!row) throw notFound();
  const intent = JSON.parse(row.intent_json) as { effect: Effect; params: Params };
  return {
    id: row.id,
    reviewId: row.review_id,
    state: row.state,
    intent,
    receipt: row.receipt_json ? (JSON.parse(row.receipt_json) as unknown) : null,
    errorCode: row.error_code,
    createdAt: new Date(row.created_at).toISOString(),
    settledAt: row.settled_at === null ? null : new Date(row.settled_at).toISOString(),
    cleanup: intent.effect === 'archive.delete' ? await archiveCleanup(env, row.id, (intent.params as { sourceIds: string[] }).sourceIds) : null,
  };
}
