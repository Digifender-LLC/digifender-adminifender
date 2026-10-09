import { archiveDeletionStatements, planArchiveDeletion } from '../deletion/archive';
import { type LedgerHead, type LedgerRecord, recordKey, verifyHead } from '../deletion/ledger';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { sha256Hex } from '../ids';

/**
 * Recovery mode (tdd.md#recovery-and-operational-controls, ops/recovery-drill.md). The operator restores the
 * databases, then deploys with RECOVERY_RUN_ID set. While it is set the API answers 503 `recovering` and jobs pause;
 * capture keeps staging mail. The scheduled pass below runs once per run ID, in order: verify the suppression
 * ledger, reapply its deletions, discard restored identity, mark interrupted inference uncertain and reconcile the
 * registry against R2. Missing or unverifiable ledger evidence blocks the run, and the API stays closed.
 */

export function recoveryRunId(env: Pick<Env, 'RECOVERY_RUN_ID'>): string | null {
  const id = env.RECOVERY_RUN_ID?.trim();
  return id ? id : null;
}

export interface LedgerCheck {
  ok: boolean;
  reason?: 'head_missing' | 'head_invalid' | 'record_missing' | 'hash_mismatch' | 'chain_broken';
  headSeq: number;
  records: number;
  sourceIds: string[];
}

/** Walks the signed chain from the head back to the first record; orphaned records off the chain are ignored. */
export async function verifyLedger(env: Pick<Env, 'DELETION_LEDGER' | 'LEDGER_SIGNING_KEY'>): Promise<LedgerCheck> {
  const fail = (reason: NonNullable<LedgerCheck['reason']>, headSeq = 0): LedgerCheck => ({ ok: false, reason, headSeq, records: 0, sourceIds: [] });
  const headObject = await env.DELETION_LEDGER.get('head.json');
  // An empty ledger is valid only when nothing was ever deleted: the operator confirms that from the drill record.
  if (!headObject) return { ok: true, headSeq: 0, records: 0, sourceIds: [] };
  const head = (await headObject.json()) as LedgerHead;
  if (!(await verifyHead(env, head))) return fail('head_invalid', head.seq);
  const sourceIds = new Set<string>();
  let wanted: string | null = head.recordHash;
  let seq = head.seq;
  let records = 0;
  while (wanted !== null) {
    // The record with the wanted hash is at or below `seq`; orphans whose head update never landed are skipped.
    let found: LedgerRecord | null = null;
    for (; seq >= 1 && !found; seq -= 1) {
      const object = await env.DELETION_LEDGER.get(recordKey(seq));
      if (!object) continue;
      const body = await object.text();
      if ((await sha256Hex(body)) === wanted) found = JSON.parse(body) as LedgerRecord;
    }
    if (!found) return fail(records === 0 ? 'record_missing' : 'chain_broken', head.seq);
    if ((await sha256Hex(canonicalJson(found))) !== wanted) return fail('hash_mismatch', head.seq);
    found.sourceIds.forEach((id) => sourceIds.add(id));
    records += 1;
    wanted = found.prevHash;
  }
  return { ok: true, headSeq: head.seq, records, sourceIds: [...sourceIds].sort() };
}

export interface RecoveryResult {
  ledger: Omit<LedgerCheck, 'sourceIds'> & { deletedSources: number };
  reapplied: number;
  alreadyDeleted: number;
  identity: { passkeys: number; sessions: number; devices: number; recoveryKeys: number; challenges: number };
  inferenceMarkedUncertain: number;
  reconcile: { live: number; rawChecked: number; rawMissing: number; missingIds: string[] };
}

const MAX_RECONCILE = 5000;
const MAX_REPORTED_IDS = 50;

/** One recovery pass; safe to call every minute: a completed or blocked run is not repeated. */
export async function runRecovery(env: Env, runId: string, now: number): Promise<'completed' | 'blocked' | 'already_done'> {
  const db = env.CONTROL_DB;
  const prior = await db.prepare('SELECT state FROM recovery_runs WHERE id = ?').bind(runId).first<{ state: string }>();
  if (prior && prior.state !== 'running') return 'already_done';
  if (!prior) await db.prepare("INSERT OR IGNORE INTO recovery_runs(id, state, started_at) VALUES (?, 'running', ?)").bind(runId, now).run();

  const ledger = await verifyLedger(env);
  if (!ledger.ok) {
    await finish(env, runId, 'blocked', now, { ledger: { ...ledger, sourceIds: undefined, deletedSources: 0 } });
    return 'blocked';
  }

  // Deletions first, before anything can be served again.
  let reapplied = 0;
  let alreadyDeleted = 0;
  for (let i = 0; i < ledger.sourceIds.length; i += 50) {
    const batch = ledger.sourceIds.slice(i, i + 50);
    const marks = batch.map(() => '?').join(',');
    const { results } = await db.prepare(`SELECT id FROM source_registry WHERE id IN (${marks}) AND state NOT IN ('deleting', 'deleted')`).bind(...batch).all<{ id: string }>();
    alreadyDeleted += batch.length - results.length;
    for (const { id } of results) {
      const plan = await planArchiveDeletion(env, [id]);
      await db.batch(await archiveDeletionStatements(env, plan, `recovery:${runId}`, now));
      reapplied += 1;
    }
  }

  // Restored credentials are never trusted again: the owner enrolls fresh through the bootstrap flow.
  const [passkeys, sessions, devices, recoveryKeys, challenges] = await db.batch([
    db.prepare('UPDATE passkeys SET revoked_at = ? WHERE revoked_at IS NULL').bind(now),
    db.prepare('UPDATE sessions SET revoked_at = ? WHERE revoked_at IS NULL').bind(now),
    db.prepare('UPDATE devices SET revoked_at = COALESCE(revoked_at, ?), fcm_token_cipher = NULL').bind(now),
    db.prepare('DELETE FROM recovery_keys'),
    db.prepare('DELETE FROM auth_challenges'),
    db.prepare('UPDATE owner SET auth_epoch = auth_epoch + 1'),
  ]);

  // A request that was in flight at the restore point may have been processed and billed: never redispatch it.
  const uncertain = await db
    .prepare("UPDATE inference_attempts SET state = 'uncertain', error_code = 'restored_in_flight', completed_at = ? WHERE state IN ('reserved', 'dispatched')")
    .bind(now)
    .run();

  // Every live source must still have its raw object; a missing one is reported, never invented.
  const { results: live } = await db
    .prepare("SELECT id, staged_key FROM source_registry WHERE state NOT IN ('deleting', 'deleted') ORDER BY id LIMIT ?")
    .bind(MAX_RECONCILE)
    .all<{ id: string; staged_key: string | null }>();
  const missing: string[] = [];
  let rawChecked = 0;
  for (const row of live) {
    if (!row.staged_key) continue;
    rawChecked += 1;
    if (!(await env.ARCHIVE_BUCKET.head(row.staged_key))) missing.push(row.id);
  }

  const result: RecoveryResult = {
    ledger: { ok: true, headSeq: ledger.headSeq, records: ledger.records, deletedSources: ledger.sourceIds.length },
    reapplied,
    alreadyDeleted,
    identity: {
      passkeys: passkeys?.meta.changes ?? 0,
      sessions: sessions?.meta.changes ?? 0,
      devices: devices?.meta.changes ?? 0,
      recoveryKeys: recoveryKeys?.meta.changes ?? 0,
      challenges: challenges?.meta.changes ?? 0,
    },
    inferenceMarkedUncertain: uncertain.meta.changes,
    reconcile: { live: live.length, rawChecked, rawMissing: missing.length, missingIds: missing.slice(0, MAX_REPORTED_IDS) },
  };
  await finish(env, runId, 'completed', now, result);
  return 'completed';
}

async function finish(env: Env, runId: string, state: 'completed' | 'blocked', now: number, result: unknown) {
  await env.CONTROL_DB.prepare("UPDATE recovery_runs SET state = ?, finished_at = ?, result_json = ? WHERE id = ? AND state = 'running'")
    .bind(state, now, JSON.stringify(result), runId)
    .run();
  console.log(JSON.stringify({ event: 'recovery_run', state }));
}
