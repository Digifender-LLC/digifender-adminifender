import { CONCERN_COLUMNS, type ConcernRow } from '../concerns/model';
import { concernSourceIds, writeConcern } from '../concerns/store';
import { assertion } from '../db/assert';
import type { Env } from '../env';
import { deterministicUuid } from '../ids';
import { knownServiceIds, loadBodyChunks, MAX_DEVICE_CHUNKS, sourceStillEligible } from '../inference/job';
import { loadSettings } from '../inference/settings';
import { isObject, type TriageOutput, validateTriage } from '../inference/triage-output';
import { CLAIM_GUARD, guardParams, type ClaimedJob, type JobResult } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow } from '../sources/registry';
import type { RuleDecision } from './rules';

interface ResultRow {
  id: string;
  source_id: string;
  source_revision: number;
  policy_revision: number;
  provenance_json: string;
  result_json: string;
}
interface CapturedRules { concernId: string; concernRevision?: number; rules: RuleDecision }

/** Only an explicit, complete UTC date/time literal can authorize an automatic reminder in this slice. */
function utcLiterals(text: string): Set<number> {
  const dates = new Set<number>();
  const pattern = /\b(\d{4}-\d{2}-\d{2})(?:T|\s+(?:at\s+)?)(\d{2}:\d{2})(?::(\d{2}))?\s*(?:Z|UTC|GMT)\b(?![A-Za-z0-9_+/-]|\s*[+-]\s*\d)/g;
  for (const match of text.matchAll(pattern)) {
    const iso = `${match[1]}T${match[2]}:${match[3] ?? '00'}.000Z`;
    const at = Date.parse(iso);
    // Date.parse rolls February 30 into March. NaN also prevents promotion when any literal is invalid.
    dates.add(Number.isFinite(at) && new Date(at).toISOString() === iso ? at : NaN);
  }
  return dates;
}

function dueInstant(deadline: TriageOutput['deadline'], evidence: string[], uncertainty: string[]): number | null {
  if (!deadline || deadline.ambiguous || !deadline.instant || !deadline.zone || uncertainty.length > 0) return null;
  try {
    if (new Intl.DateTimeFormat('en-US', { timeZone: deadline.zone }).resolvedOptions().timeZone !== 'UTC') return null;
    const wording = utcLiterals(deadline.text);
    const cited = new Set(evidence.flatMap((text) => [...utcLiterals(text)]));
    const at = Date.parse(deadline.instant);
    if (!Number.isFinite(at) || new Date(at).toISOString().slice(0, 19) !== deadline.instant.slice(0, 19)) return null;
    // No invented date parts or changed instant: both complete literals must agree exactly. Multiple
    // dates, relative wording and other zones (including DST overlaps) remain reviewable proposals.
    return wording.size === 1 && cited.size === 1 && wording.has(at) && cited.has(at) ? at : null;
  } catch {
    return null;
  }
}

/**
 * Replay-safe projection of one immutable, validated result. Rules are the frozen source decision, never
 * today's rule set. Any intervening owner edit or new evidence keeps this interpretation as a proposal.
 * The result receipt, concern event, sync change, notifications and job settlement commit in one batch.
 */
export async function applyModelTriageJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  if (!isObject(job.input) || typeof job.input.resultId !== 'string') return { kind: 'fail', errorCode: 'missing_result' };
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT id, source_id, source_revision, policy_revision, provenance_json, result_json FROM triage_results WHERE id = ?')
    .bind(job.input.resultId).first<ResultRow>();
  if (!row) return { kind: 'complete', output: { skipped: 'result_missing' } };
  const result = JSON.parse(row.result_json) as { status: string; output: TriageOutput; application?: unknown };
  if (result.application) return { kind: 'complete', output: { skipped: 'already_applied' } };
  const provenance = JSON.parse(row.provenance_json) as { engine: string; route: string; task: string };
  const receiptGuard = `EXISTS (SELECT 1 FROM triage_results WHERE id = ? AND result_json = ?)`;
  const receiptParams = [row.id, row.result_json];
  const receipt = (application: Record<string, unknown>) => db.prepare(`UPDATE triage_results SET result_json = ? WHERE id = ? AND result_json = ? AND ${CLAIM_GUARD}`)
    .bind(JSON.stringify({ ...result, application }), row.id, row.result_json, ...guardParams(job));
  const skip = (reason: string): JobResult => ({ kind: 'complete', output: { skipped: reason }, statements: [receipt({ status: 'skipped', reason })] });
  if (job.sourceId !== row.source_id || job.inputRevision !== row.source_revision || provenance.engine !== 'inference' || provenance.task !== 'triage' || !['local', 'cloud'].includes(provenance.route)) return skip('invalid_provenance');
  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
  if (!source || source.revision !== row.source_revision || source.state !== 'ready' || source.admission !== 'normal') return skip('source_changed');
  const settings = await loadSettings(db);
  if (settings.policyRevision !== row.policy_revision) return skip('policy_changed');
  if (!['hybrid', 'local_only'].includes(source.privacy) || settings.inference.mode === 'no_ai' || (provenance.route === 'cloud' && (source.privacy === 'local_only' || settings.inference.mode === 'local_only'))) return skip('privacy_changed');
  const capturedRow = await db.prepare("SELECT result_json FROM triage_results WHERE source_id = ? AND source_revision = ? AND json_extract(provenance_json, '$.engine') = 'rules-v1' ORDER BY created_at, id LIMIT 1")
    .bind(source.id, source.revision).first<{ result_json: string }>();
  if (!capturedRow) return skip('rules_missing');
  const captured = JSON.parse(capturedRow.result_json) as CapturedRules;
  if (!captured.concernId || !captured.rules) return skip('rules_missing');
  // Historical rules-v1 receipts predate concernRevision. Their source-linked creation event is immutable.
  const initial = await db.prepare("SELECT to_revision FROM concern_events WHERE concern_id = ? AND json_extract(after_json, '$.detail.sourceId') = ? AND kind IN ('created', 'evidence_added', 'evidence_linked') ORDER BY to_revision LIMIT 1")
    .bind(captured.concernId, source.id).first<{ to_revision: number }>();
  const baseRevision = captured.concernRevision ?? initial?.to_revision;
  if (!baseRevision) return skip('revision_missing');
  const before = await db.prepare(`SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns WHERE id = ?`).bind(captured.concernId).first<ConcernRow>();
  const membership = await db.prepare('SELECT 1 AS found FROM concern_sources WHERE concern_id = ? AND source_id = ?').bind(captured.concernId, source.id).first();
  if (!before || !membership || before.revision < baseRevision) return skip('concern_changed');
  const changed = await db.prepare("SELECT 1 AS found FROM concern_events WHERE concern_id = ? AND to_revision > ? AND kind <> 'mark_read' LIMIT 1").bind(before.id, baseRevision).first();
  if (changed) return skip('concern_changed');

  const body = await loadBodyChunks(env, source, MAX_DEVICE_CHUNKS);
  if (!body) return { kind: 'retry', errorCode: 'text_unavailable' };
  const checked = validateTriage(result.output, new Map(body.chunks.map((chunk) => [chunk.id, chunk.text.length])), await knownServiceIds(db));
  if (!checked.ok) return skip('output_no_longer_valid');
  const output = checked.value;
  if (output.evidence.length === 0) return skip('evidence_missing');
  const ranks = { routine: 0, important: 1, urgent: 2, critical: 3 };
  const importance = captured.rules.importance !== null || ranks[before.importance] >= ranks[output.importance] ? before.importance : output.importance;
  const serviceId = before.service_id ?? (captured.rules.serviceIds.length === 0 ? output.serviceIds[0] ?? null : null);
  const maySetDue = before.due_at === null && before.due_source_text === null && captured.rules.dueText === null;
  const citedText = output.evidence.map((item) => body.chunks.find((chunk) => chunk.id === item.chunkId)!.text.slice(item.start, item.end));
  const dueAt = maySetDue ? dueInstant(output.deadline, citedText, output.uncertainty) : before.due_at;
  const dueText = maySetDue ? output.deadline?.text ?? null : before.due_source_text;
  const dueZone = maySetDue && dueAt !== null ? output.deadline!.zone! : before.due_zone;
  const dueChanged = dueAt !== before.due_at || dueZone !== before.due_zone || dueText !== before.due_source_text;
  const after: ConcernRow = {
    ...before, revision: before.revision + 1, importance, service_id: serviceId,
    due_at: dueAt, due_zone: dueZone, due_source_text: dueText,
    due_revision: before.due_revision + (dueChanged ? 1 : 0), updated_at: now,
    // Preserve the deterministic source-evidence digest. A model interpretation does not make a later
    // identical body new evidence; model_triaged and this revision carry its separate attention signal.
  };
  const eligible = sourceStillEligible(source.id, source.revision, source.privacy, row.policy_revision);
  const serviceGuard = serviceId !== null && serviceId !== before.service_id ? { sql: 'EXISTS (SELECT 1 FROM service_catalog WHERE id = ?)', params: [serviceId] } : { sql: '1', params: [] };
  const projectionFence = `${eligible.sql} AND ${receiptGuard} AND ${serviceGuard.sql}`;
  const projectionParams = [...eligible.params, ...receiptParams, ...serviceGuard.params];
  const guard = { sql: `${CLAIM_GUARD} AND ${projectionFence}`, params: [...guardParams(job), ...projectionParams] };
  const sourceIds = await concernSourceIds(db, before.id);
  const application = { status: 'applied', concernId: before.id, concernRevision: after.revision, deadline: !output.deadline ? 'none' : !maySetDue ? 'preserved' : dueAt === null ? 'proposed' : 'applied' };
  return {
    kind: 'complete', output: application,
    statements: [
      // Abort a raced eligibility/result change; runner retries against the current source and concern.
      assertion(db, `NOT (${CLAIM_GUARD}) OR (${projectionFence})`, ...guardParams(job), ...projectionParams),
      ...writeConcern(db, { before, after, kind: 'model_triaged', eventId: await deterministicUuid(`model-triaged:${row.id}`), sourceIds, now, meaningful: false, guard,
        eventDetail: { sourceId: source.id, resultId: row.id, sourceRevision: source.revision, policyRevision: row.policy_revision } }),
      receipt(application),
    ],
  };
}
