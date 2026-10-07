import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

// Matches the version-1 canonical representation in backend/src/http/idempotency.ts.
export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}

const hash = (value) => createHash('sha256').update(canonicalJson(value)).digest('hex');
const HEX = /^[0-9a-f]{64}$/;
function requireValue(condition, code) { if (!condition) throw new Error(code); }
function exactKeys(value, expected) {
  return value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).sort().join(',') === [...expected].sort().join(',');
}

/**
 * Read-only suppression verifier, compatible with the Worker's signed ledger.
 * expectedHead must come from a fresh, independently obtained current-head read;
 * a backup's own head cannot establish freshness. No file or network I/O here.
 * The command-line tests use synthetic keys only; this is not a restore command.
 */
export function verifySuppressionLedger({ head, records, signingKey, expectedHead }) {
  requireValue(exactKeys(head, ['seq', 'recordHash', 'signature']), 'invalid_head');
  requireValue(Number.isSafeInteger(head.seq) && head.seq > 0 && HEX.test(head.recordHash)
    && typeof head.signature === 'string' && /^[A-Za-z0-9_-]{43}$/.test(head.signature), 'invalid_head');
  requireValue(typeof signingKey === 'string' && signingKey.length >= 32, 'unconfigured_key');
  requireValue(expectedHead && head.seq === expectedHead.seq && head.recordHash === expectedHead.recordHash,
    'current_head_required');
  const signature = createHmac('sha256', signingKey)
    .update(`adminifender-ledger-v1\n${head.seq}\n${head.recordHash}`).digest();
  requireValue(timingSafeEqual(signature, Buffer.from(head.signature, 'base64url')), 'invalid_signature');
  requireValue(Array.isArray(records) && records.length <= 100_000, 'invalid_records');

  const byHash = new Map();
  const sequences = new Set();
  for (const record of records) {
    requireValue(exactKeys(record, ['version', 'seq', 'operationId', 'sourceIds', 'deletedAt', 'prevHash'])
      && record.version === 1 && Number.isSafeInteger(record.seq) && record.seq > 0
      && typeof record.operationId === 'string' && record.operationId.length > 0
      && Array.isArray(record.sourceIds) && record.sourceIds.length > 0
      && record.sourceIds.every((id) => typeof id === 'string' && id.length > 0)
      && new Set(record.sourceIds).size === record.sourceIds.length
      && record.sourceIds.every((id, i) => i === 0 || record.sourceIds[i - 1] < id)
      && typeof record.deletedAt === 'string' && Number.isFinite(Date.parse(record.deletedAt))
      && (record.prevHash === null || HEX.test(record.prevHash)), 'invalid_record');
    requireValue(!sequences.has(record.seq), 'duplicate_sequence');
    sequences.add(record.seq);
    byHash.set(hash(record), record);
  }

  const suppressed = new Set();
  let recordHash = head.recordHash;
  let previousSeq = Number.POSITIVE_INFINITY;
  let count = 0;
  while (recordHash !== null) {
    const record = byHash.get(recordHash);
    requireValue(record, 'missing_chain_record');
    requireValue(record.seq < previousSeq && (count !== 0 || record.seq === head.seq), 'invalid_chain_sequence');
    for (const id of record.sourceIds) suppressed.add(id);
    previousSeq = record.seq;
    recordHash = record.prevHash;
    count++;
  }
  // Sequence gaps are valid: a losing conditional head update leaves an orphan.
  return { suppressedSourceIds: [...suppressed].sort(), chainRecords: count, orphanRecords: records.length - count };
}

/** Audit sanitized counts/opaque IDs extracted from isolated restored resources. Never mutates data. */
export function auditRestoredState(state, verifiedLedger) {
  requireValue(verifiedLedger && Array.isArray(verifiedLedger.suppressedSourceIds), 'verified_ledger_required');
  requireValue(state && Array.isArray(state.visibleSourceIds) && Array.isArray(state.searchSourceIds)
    && Array.isArray(state.downloadableSourceIds) && Array.isArray(state.replayJobs), 'invalid_restore_summary');
  const issues = [];
  const suppressed = new Set(verifiedLedger.suppressedSourceIds);
  for (const name of ['visibleSourceIds', 'searchSourceIds', 'downloadableSourceIds']) {
    if (state[name].some((id) => suppressed.has(id))) issues.push(`suppressed_source_in_${name}`);
  }
  for (const name of ['passkeys', 'sessions', 'recoveryKeys', 'fcmRegistrations', 'authChallenges', 'actionReviews']) {
    requireValue(Number.isSafeInteger(state[name]) && state[name] >= 0, 'invalid_identity_count');
    if (state[name] !== 0) issues.push(`restored_${name}_remain`);
  }
  if (state.externalDispatchPaused !== true) issues.push('external_dispatch_not_paused');
  if (state.freshEnrollmentRequired !== true) issues.push('fresh_enrollment_not_required');
  const replaySafe = new Set(['parse', 'triage', 'extract', 'cleanup', 'export']);
  for (const job of state.replayJobs) {
    if (!job || !replaySafe.has(job.kind) || job.dispatchedAt !== null) {
      issues.push('unsafe_job_replay');
      break;
    }
  }
  return { passed: issues.length === 0, issues };
}
