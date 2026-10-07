import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { GATES, checkEvidence, readEvidenceArtifact } from './release_preflight.mjs';

const artifact = Buffer.from('Sanitized synthetic fixture only.');
const sha256 = createHash('sha256').update(artifact).digest('hex');
const snapshot = { sourceRevision: '1'.repeat(40), trackedWorktreeClean: true };
const now = new Date('2026-10-07T00:00:00Z');
function complete() {
  return { version: 1, sourceRevision: snapshot.sourceRevision, checks: Object.entries(GATES).map(([gate, contract]) => ({
    gate, status: 'passed', scope: contract.scope, sourceRevision: snapshot.sourceRevision,
    observedAt: now.toISOString(), artifact: '.quality-reports/release-evidence/fixture.txt', sha256,
  })) };
}
const check = (registry, state = snapshot) => checkEvidence(registry, state, () => artifact, now);

test('default empty evidence blocks every gate', () => {
  const result = check({ version: 1, sourceRevision: snapshot.sourceRevision, checks: [] });
  assert.equal(result.readiness, 'blocked');
  assert.equal(result.blockers.length, Object.keys(GATES).length);
});
test('complete metadata remains subject to review and never asserts production acceptance', () => {
  const result = check(complete());
  assert.equal(result.readiness, 'evidence_complete_review_required');
  assert.equal(result.productionAccepted, false);
});
test('local mocks cannot satisfy Play/device/recovery provenance', () => {
  for (const gate of ['encrypted-restore', 'identity-association', 'device-model-ocr']) {
    const registry = complete(); registry.checks.find((item) => item.gate === gate).scope = 'local';
    assert.ok(check(registry).blockers.includes(`${gate}:wrong_evidence_scope`));
  }
});
test('stale revision, dirty tracked source, wrong hash and future observations block release', () => {
  const revision = complete(); revision.sourceRevision = '2'.repeat(40);
  assert.ok(check(revision).blockers.includes('evidence_revision_mismatch'));
  assert.ok(check(complete(), { ...snapshot, trackedWorktreeClean: false }).blockers.includes('tracked_worktree_dirty'));
  assert.ok(check(complete(), { ...snapshot, untrackedFilesPresent: true }).blockers.includes('untracked_files_present'));
  for (const [field, value, issue] of [
    ['sourceRevision', '2'.repeat(40), 'source_revision_mismatch'], ['sha256', '0'.repeat(64), 'artifact_hash_mismatch'],
    ['observedAt', '2026-10-08T00:00:00Z', 'invalid_observation_time'],
  ]) {
    const registry = complete(); registry.checks[0][field] = value;
    assert.ok(check(registry).blockers.includes(`backend-check:${issue}`));
  }
});
test('invalid command-line flags are rejected instead of ignored', () => {
  const result = spawnSync(process.execPath, [fileURLToPath(new URL('./release_preflight.mjs', import.meta.url)), '--unknown'], { encoding: 'utf8' });
  assert.equal(result.status, 2);
  assert.match(result.stderr, /usage:/);
});
test('unknown/duplicate gates and unreadable artifacts fail closed', () => {
  const duplicate = complete(); duplicate.checks.push(duplicate.checks[0]);
  assert.throws(() => check(duplicate), /unknown_or_duplicate_gate/);
  const unknown = complete(); unknown.checks[0].gate = 'invented';
  assert.throws(() => check(unknown), /unknown_or_duplicate_gate/);
  assert.equal(checkEvidence(complete(), snapshot, () => { throw new Error('missing'); }, now).readiness, 'blocked');
});
test('artifact access is confined to the evidence directory, including resolved symlinks', () => {
  const root = mkdtempSync(join(tmpdir(), 'adminifender-release-'));
  try {
    const dir = join(root, '.quality-reports/release-evidence'); mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, 'valid.txt'), artifact);
    writeFileSync(join(root, 'outside.txt'), 'outside');
    symlinkSync(join(root, 'outside.txt'), join(dir, 'escape.txt'));
    assert.deepEqual(readEvidenceArtifact(root, '.quality-reports/release-evidence/valid.txt'), artifact);
    for (const path of ['outside.txt', '.quality-reports/release-evidence/../../outside.txt', '.quality-reports/release-evidence/escape.txt']) {
      assert.throws(() => readEvidenceArtifact(root, path), /artifact_path/);
    }
  } finally { rmSync(root, { recursive: true, force: true }); }
});
test('an evidence directory symlink cannot widen allowed access', () => {
  const root = mkdtempSync(join(tmpdir(), 'adminifender-release-root-'));
  const outside = mkdtempSync(join(tmpdir(), 'adminifender-release-outside-'));
  try {
    mkdirSync(join(root, '.quality-reports'));
    symlinkSync(outside, join(root, '.quality-reports/release-evidence'));
    writeFileSync(join(outside, 'report.txt'), artifact);
    assert.throws(() => readEvidenceArtifact(root, '.quality-reports/release-evidence/report.txt'), /artifact_path/);
  } finally {
    rmSync(root, { recursive: true, force: true });
    rmSync(outside, { recursive: true, force: true });
  }
});
