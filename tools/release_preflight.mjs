#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

// Scope is evidence provenance, never an automatic assertion of product acceptance.
export const GATES = Object.freeze({
  'backend-check': { scope: 'local', requires: 'Worker typecheck, contracts, migrations, runtime tests and dry-run bundle' },
  'android-check': { scope: 'local', requires: 'Kotlin compilation, JVM tests, lint and release bundle build' },
  'instrumentation-minimum': { scope: 'emulator', requires: 'API 31 Room migration, Keystore, process-death and UI checks' },
  'instrumentation-current': { scope: 'emulator', requires: 'Instrumentation at the owner device API level' },
  'staging-isolation': { scope: 'staging', requires: 'Distinct bindings, Android package, Firebase project and synthetic-only data' },
  'email-round-trip': { scope: 'staging', requires: 'Actual delivery, blocking, near-limit MIME and interrupted capture behavior (AT-02)' },
  'identity-association': { scope: 'play-device', requires: 'HTTPS association, Play signing certificate, passkey and second-authenticator recovery (AT-01)' },
  'device-model-ocr': { scope: 'device', requires: 'S24+ CPU model, manifest/license/hash, 50-case evaluation, OCR, thermal/cancellation (AT-04/05/07)' },
  'cloud-model-cost': { scope: 'staging', requires: 'Selected cloud model smoke, account headroom/accounting and cost/capacity limits (AT-07/16)' },
  'encrypted-restore': { scope: 'isolated-recovery', requires: 'Encrypted backup decryption, D1/objects/FTS, independent current ledger, fresh auth and measured RPO/RTO (AT-15)' },
  'play-device-acceptance': { scope: 'play-device', requires: 'Signed private Play install/update, live FCM, accessibility, offline sync and forward-fix rollout (AT-09/10/15)' },
  'p1-acceptance': { scope: 'play-device', requires: 'Case-level SRS P1 results for AT-01 through AT-11, AT-15, AT-16 and P1 portions of AT-17' },
});

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const git = (root, args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();

export function inventory(root = ROOT) {
  const fixed = ['backend/.nvmrc', 'backend/package.json', 'backend/package-lock.json', 'backend/wrangler.jsonc',
    'android/gradle/wrapper/gradle-wrapper.properties', 'android/gradle/libs.versions.toml', 'android/app/build.gradle.kts'];
  const migrations = ['control', 'archive'].flatMap((kind) => readdirSync(join(root, 'backend/migrations', kind))
    .filter((name) => name.endsWith('.sql')).sort().map((name) => `backend/migrations/${kind}/${name}`));
  const files = [...fixed, ...migrations].map((path) => ({ path, sha256: sha256(readFileSync(join(root, path))) }));
  return { sourceRevision: git(root, ['rev-parse', 'HEAD']),
    trackedWorktreeClean: git(root, ['status', '--porcelain', '--untracked-files=no']) === '',
    untrackedFilesPresent: git(root, ['ls-files', '--others', '--exclude-standard']) !== '',
    inputs: files, migrations: Object.fromEntries(['control', 'archive'].map((kind) => [kind,
      migrations.filter((path) => path.includes(`/migrations/${kind}/`)).map((path) => path.split('/').at(-1))])),
    // An input inventory is deliberately not a release/model/compatibility manifest.
    inventorySha256: sha256(JSON.stringify(files)) };
}

export function readEvidenceArtifact(root, artifact) {
  if (typeof artifact !== 'string' || !artifact.startsWith('.quality-reports/release-evidence/')) throw new Error('artifact_path');
  const allowed = realpathSync(join(root, '.quality-reports/release-evidence'));
  if (allowed !== join(realpathSync(root), '.quality-reports/release-evidence')) throw new Error('artifact_path');
  const path = realpathSync(resolve(root, artifact));
  const child = relative(allowed, path);
  if (child === '' || child.startsWith(`..${sep}`) || child === '..' || isAbsolute(child)) throw new Error('artifact_path');
  if (!/\.(json|txt|xml|md|html)$/.test(path)) throw new Error('artifact_extension');
  const stat = statSync(path);
  if (!stat.isFile() || stat.size > 16 * 1024 * 1024) throw new Error('artifact_size');
  return readFileSync(path);
}

/** Checks metadata and artifact integrity only. It never executes an evidence command. */
export function checkEvidence(registry, snapshot, readArtifact, now = new Date()) {
  const blockers = [];
  const checks = registry?.checks;
  if (registry?.version !== 1 || !Array.isArray(checks)) throw new Error('invalid_registry');
  if (registry.sourceRevision !== snapshot.sourceRevision) blockers.push('evidence_revision_mismatch');
  if (!snapshot.trackedWorktreeClean) blockers.push('tracked_worktree_dirty');
  if (snapshot.untrackedFilesPresent) blockers.push('untracked_files_present');
  const seen = new Set();
  for (const check of checks) {
    if (!check || !Object.hasOwn(GATES, check.gate) || seen.has(check.gate)) throw new Error('unknown_or_duplicate_gate');
    seen.add(check.gate);
  }
  const gates = Object.entries(GATES).map(([gate, contract]) => {
    const record = checks.find((check) => check.gate === gate);
    let issue = null;
    if (!record || record.status !== 'passed') issue = 'missing_passed_evidence';
    else if (record.scope !== contract.scope) issue = 'wrong_evidence_scope';
    else if (record.sourceRevision !== snapshot.sourceRevision) issue = 'source_revision_mismatch';
    else if (typeof record.observedAt !== 'string' || !/T.*Z$/.test(record.observedAt)
      || !Number.isFinite(Date.parse(record.observedAt)) || Date.parse(record.observedAt) > now.getTime()) issue = 'invalid_observation_time';
    else if (typeof record.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(record.sha256)) issue = 'invalid_artifact_hash';
    else {
      try { if (sha256(readArtifact(record.artifact)) !== record.sha256) issue = 'artifact_hash_mismatch'; }
      catch { issue = 'artifact_unavailable_or_disallowed'; }
    }
    if (issue) blockers.push(`${gate}:${issue}`);
    return { gate, ...contract, evidence: issue ? 'missing_or_invalid' : 'recorded', issue };
  });
  return { readiness: blockers.length ? 'blocked' : 'evidence_complete_review_required',
    productionAccepted: false, blockers, gates,
    limits: ['Artifact hashes authenticate bytes, not the truth or completeness of their claims.',
      'This read-only check does not build, test, provision, deploy, sign, publish, contact providers or prove live acceptance.',
      'Review case-level evidence and current configuration before activation; synthetic recovery is not isolated recovery.'] };
}

function main() {
  const args = process.argv.slice(2);
  const json = args.includes('--json');
  const evidenceAt = args.indexOf('--evidence');
  const allowed = args.filter((_, i) => evidenceAt < 0 || (i !== evidenceAt && i !== evidenceAt + 1));
  if ((evidenceAt >= 0 && !args[evidenceAt + 1]) || allowed.some((arg) => arg !== '--json')) {
    throw new Error('usage: node tools/release_preflight.mjs [--json] [--evidence .quality-reports/release-evidence/registry.json]');
  }
  const snapshot = inventory();
  const registry = evidenceAt >= 0
    ? JSON.parse(readEvidenceArtifact(ROOT, args[evidenceAt + 1]).toString('utf8'))
    : { version: 1, sourceRevision: snapshot.sourceRevision, checks: [] };
  const report = { version: 1, ...snapshot, ...checkEvidence(registry, snapshot, (path) => readEvidenceArtifact(ROOT, path)) };
  if (json) process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
  else {
    process.stdout.write(`Release preflight: ${report.readiness}\nSource: ${snapshot.sourceRevision}\n`);
    for (const gate of report.gates) process.stdout.write(`${gate.evidence === 'recorded' ? 'RECORDED' : 'BLOCKED'} ${gate.gate}: ${gate.requires}\n`);
    for (const limit of report.limits) process.stdout.write(`${limit}\n`);
  }
  process.exitCode = report.blockers.length ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); } catch (error) {
    // Never echo input content or parser errors that could contain private evidence.
    process.stderr.write(`Release preflight failed: ${error instanceof Error && error.message.startsWith('usage:') ? error.message : 'invalid or unavailable input'}\n`);
    process.exitCode = 2;
  }
}
