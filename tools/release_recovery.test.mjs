import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac } from 'node:crypto';
import { canonicalJson, verifySuppressionLedger, auditRestoredState } from './release_recovery.mjs';

// Deliberately public fixture key. Never used by the app or a remote environment.
const signingKey = 'synthetic-release-recovery-test-key-only';
const digest = (record) => createHash('sha256').update(canonicalJson(record)).digest('hex');
const record = (seq, sourceIds, prevHash = null) => ({ version: 1, seq,
  operationId: `synthetic-operation-${seq}`, sourceIds,
  deletedAt: '2026-10-07T00:00:00.000Z', prevHash });
function fixture() {
  const first = record(1, ['deleted-a']);
  const orphan = record(2, ['orphan-not-committed'], digest(first));
  const last = record(3, ['deleted-b'], digest(first));
  const head = { seq: 3, recordHash: digest(last) };
  head.signature = createHmac('sha256', signingKey)
    .update(`adminifender-ledger-v1\n${head.seq}\n${head.recordHash}`).digest('base64url');
  return { head, expectedHead: { seq: head.seq, recordHash: head.recordHash }, signingKey,
    records: [last, orphan, first] };
}
function cleanState() {
  return { visibleSourceIds: ['retained'], searchSourceIds: ['retained'], downloadableSourceIds: ['retained'],
    passkeys: 0, sessions: 0, recoveryKeys: 0, fcmRegistrations: 0, authChallenges: 0, actionReviews: 0,
    externalDispatchPaused: true, freshEnrollmentRequired: true,
    replayJobs: [{ kind: 'parse', dispatchedAt: null }] };
}

test('authenticates the current chain, accepts a valid sequence gap and excludes orphan suppressions', () => {
  assert.deepEqual(verifySuppressionLedger(fixture()), {
    suppressedSourceIds: ['deleted-a', 'deleted-b'], chainRecords: 2, orphanRecords: 1,
  });
});
test('canonical bytes match the Worker version-1 sorted JSON contract', () => {
  assert.equal(canonicalJson({ z: ['b', 'a'], a: { y: true, x: null } }), '{"a":{"x":null,"y":true},"z":["b","a"]}');
});
test('missing independently current head and an authentic stale head both fail closed', () => {
  assert.throws(() => verifySuppressionLedger({ ...fixture(), expectedHead: null }), /current_head_required/);
  assert.throws(() => verifySuppressionLedger({ ...fixture(), expectedHead: { seq: 4, recordHash: 'a'.repeat(64) } }), /current_head_required/);
});
test('wrong key and changed signature are rejected', () => {
  assert.throws(() => verifySuppressionLedger({ ...fixture(), signingKey: 'wrong'.repeat(10) }), /invalid_signature/);
  const data = fixture(); data.head.signature = 'a'.repeat(43);
  assert.throws(() => verifySuppressionLedger(data), /invalid_signature/);
});
test('missing predecessor, modified record, and duplicate sequence are rejected', () => {
  const missing = fixture(); missing.records.pop();
  assert.throws(() => verifySuppressionLedger(missing), /missing_chain_record/);
  const changed = fixture(); changed.records[0].sourceIds = ['undelete-attempt'];
  assert.throws(() => verifySuppressionLedger(changed), /missing_chain_record/);
  const duplicate = fixture(); duplicate.records.push(duplicate.records[0]);
  assert.throws(() => verifySuppressionLedger(duplicate), /duplicate_sequence/);
});
test('unknown fields and malformed head are not silently ignored', () => {
  const unknown = fixture(); unknown.records[0].unexpected = true;
  assert.throws(() => verifySuppressionLedger(unknown), /invalid_record/);
  const invalid = fixture(); invalid.head.seq = 0;
  assert.throws(() => verifySuppressionLedger(invalid), /invalid_head/);
});
test('a sanitized safe restore summary passes without establishing live recovery', () => {
  assert.deepEqual(auditRestoredState(cleanState(), verifySuppressionLedger(fixture())), { passed: true, issues: [] });
});
test('suppression is required for reads, search and downloads', () => {
  for (const name of ['visibleSourceIds', 'searchSourceIds', 'downloadableSourceIds']) {
    const state = cleanState(); state[name].push('deleted-a');
    assert.deepEqual(auditRestoredState(state, verifySuppressionLedger(fixture())).issues, [`suppressed_source_in_${name}`]);
  }
});
test('all restored identity/review material is rejected and counts must be present', () => {
  for (const name of ['passkeys', 'sessions', 'recoveryKeys', 'fcmRegistrations', 'authChallenges', 'actionReviews']) {
    const state = cleanState(); state[name] = 1;
    assert.equal(auditRestoredState(state, verifySuppressionLedger(fixture())).passed, false);
    delete state[name];
    assert.throws(() => auditRestoredState(state, verifySuppressionLedger(fixture())), /invalid_identity_count/);
  }
});
test('resuming external dispatch, skipping fresh enrollment or replaying dispatched work fails', () => {
  for (const field of ['externalDispatchPaused', 'freshEnrollmentRequired']) {
    assert.equal(auditRestoredState({ ...cleanState(), [field]: false }, verifySuppressionLedger(fixture())).passed, false);
  }
  for (const job of [{ kind: 'notify', dispatchedAt: null }, { kind: 'parse', dispatchedAt: 1 }, { kind: 'unknown', dispatchedAt: null }]) {
    assert.deepEqual(auditRestoredState({ ...cleanState(), replayJobs: [job] }, verifySuppressionLedger(fixture())).issues, ['unsafe_job_replay']);
  }
});
