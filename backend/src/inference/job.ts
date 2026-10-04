import { BODY_GENERATION } from '../archive/publish';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { canonicalJson } from '../http/idempotency';
import { deterministicUuid, sha256Hex } from '../ids';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import { type Reservation, type UsageKind, releaseStatements, reserve, settleStatements } from './budget';
import { CLOUD_MAX_CHUNKS, type Message, type TextModel, repairMessages, runCloud, triageMessages, worstCaseInputTokens, CLOUD_OUTPUT_TOKENS } from './cloud';
import { type Privacy, type Readiness, type TaskKind, type Thermal, cloudGate, localReady, route } from './routing';
import { POLICY_REVISION_SQL, type Settings, loadSettings } from './settings';
import { resolveTriageQuotes } from './quotes';
import { type TriageOutput, normalizeTriage, parseModelJson, validateTriage } from './triage-output';

export const INFER_TASK = 'triage';
/** Device chunk lists are bounded; the phone packs them into its own token-sized pieces. */
export const MAX_DEVICE_CHUNKS = 64;
const LOCAL_MODEL_PENDING = 'device';

/** Enqueues model triage after deterministic triage, in the caller's claim-guarded batch. One per source revision. */
export function inferJobStatements(db: D1Database, sourceId: string, now: number, guard: { sql: string; params: unknown[] }): D1PreparedStatement[] {
  return [
    db.prepare(
      `INSERT OR IGNORE INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at)
       SELECT ?, 'infer:${INFER_TASK}:' || id || ':' || revision, 'infer', id, revision, 'pending', ?, ?, ? FROM source_registry WHERE id = ? AND ${guard.sql}`,
    ).bind(crypto.randomUUID(), now, JSON.stringify({ sourceId, task: INFER_TASK }), now, sourceId, ...guard.params),
    db.prepare(
      `INSERT OR IGNORE INTO outbox(id, dedupe_key, kind, reference_id, state, next_at)
       SELECT ?, 'job:' || j.id, 'job', j.id, 'pending', ? FROM jobs j JOIN source_registry r ON j.dedupe_key = 'infer:${INFER_TASK}:' || r.id || ':' || r.revision WHERE r.id = ?`,
    ).bind(crypto.randomUUID(), now, sourceId),
  ];
}

export interface BodyChunk {
  id: string;
  text: string;
  hash: string;
}

/** Body chunks of the published generation, in order. */
export async function loadBodyChunks(env: Env, source: RegistryRow, limit: number): Promise<{ subject: string; chunks: BodyChunk[] } | null> {
  const archive = archiveFor(env, source);
  if (!archive) return null;
  const head = await archive.prepare('SELECT subject FROM sources WHERE id = ?').bind(source.id).first<{ subject: string }>();
  if (!head) return null;
  const { results } = await archive
    .prepare('SELECT id, text, text_hash FROM chunks WHERE source_id = ? AND attachment_id IS NULL AND generation_id = ? ORDER BY ordinal LIMIT ?')
    .bind(source.id, BODY_GENERATION, limit)
    .all<{ id: string; text: string; text_hash: string }>();
  return { subject: head.subject, chunks: results.map((r) => ({ id: r.id, text: r.text, hash: r.text_hash })) };
}

/** Content-free digest of exactly what a model is given, so a result can be matched to its input. */
export async function inputDigest(source: RegistryRow, task: string, chunks: BodyChunk[]): Promise<string> {
  return sha256Hex(canonicalJson({ sourceId: source.id, revision: source.revision, task, chunks: chunks.map((c) => [c.id, c.hash]) }));
}

export async function knownServiceIds(db: D1Database): Promise<Set<string>> {
  return new Set((await db.prepare('SELECT id FROM service_catalog').all<{ id: string }>()).results.map((r) => r.id));
}

/** The source still has the revision, admission and privacy the work was routed under, and policy is unchanged. */
export function sourceStillEligible(sourceId: string, revision: number, privacy: string, policyRevision: number): { sql: string; params: unknown[] } {
  return {
    sql: `EXISTS (SELECT 1 FROM source_registry WHERE id = ? AND revision = ? AND state = 'ready' AND admission = 'normal' AND privacy = ?)
      AND ${POLICY_REVISION_SQL} = ?`,
    params: [sourceId, revision, privacy, policyRevision],
  };
}

/** Stores a validated output as the source's model triage result, only while the source is still eligible. */
export async function resultStatement(
  db: D1Database,
  attempt: { id: string; jobId: string; route: 'local' | 'cloud'; modelId: string },
  source: { id: string; revision: number; privacy: string },
  policyRevision: number,
  output: TriageOutput,
  now: number,
): Promise<D1PreparedStatement> {
  const eligible = sourceStillEligible(source.id, source.revision, source.privacy, policyRevision);
  return db
    .prepare(`INSERT OR IGNORE INTO triage_results(id, source_id, source_revision, policy_revision, provenance_json, result_json, created_at) SELECT ?, ?, ?, ?, ?, ?, ? WHERE ${eligible.sql}`)
    .bind(
      await deterministicUuid(`inference:${attempt.jobId}`),
      source.id,
      source.revision,
      policyRevision,
      JSON.stringify({ engine: 'inference', task: INFER_TASK, route: attempt.route, modelId: attempt.modelId, attemptId: attempt.id }),
      JSON.stringify({ status: 'inferred', output }),
      now,
      ...eligible.params,
    );
}

export function queueLocalStatement(
  db: D1Database,
  fields: {
    id: string;
    jobId: string;
    task?: string;
    usageKind?: UsageKind;
    source: { id: string; revision: number } | null;
    chunkIds?: string[] | null;
    policyRevision: number;
    digest: string;
    reason: string;
    repair: boolean;
  },
  now: number,
  guard?: { sql: string; params: unknown[] },
): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO inference_attempts(id, job_id, route, model_id, policy_revision, input_digest, state, reservation_json, task, usage_kind, source_id, source_revision, reason, repair, chunk_ids_json, created_at)
       SELECT ?, ?, 'local', '${LOCAL_MODEL_PENDING}', ?, ?, 'queued', '{}', ?, ?, ?, ?, ?, ?, ?, ?${guard ? ` WHERE ${guard.sql}` : ''}`,
    )
    .bind(
      fields.id,
      fields.jobId,
      fields.policyRevision,
      fields.digest,
      fields.task ?? INFER_TASK,
      fields.usageKind ?? 'automatic',
      fields.source?.id ?? null,
      fields.source?.revision ?? null,
      fields.reason,
      fields.repair ? 1 : 0,
      fields.chunkIds ? JSON.stringify(fields.chunkIds) : null,
      now,
      ...(guard?.params ?? []),
    );
}

export const RUNNING_GUARD = "EXISTS (SELECT 1 FROM jobs WHERE id = ? AND lease_token = ? AND state = 'running')";

interface AttemptRow {
  id: string;
  route: 'local' | 'cloud';
  state: string;
  reservation_json: string;
}

/** Fresh readiness of any active device for this kind of task. */
export async function anyDeviceReady(db: D1Database, now: number, task: TaskKind = 'background'): Promise<boolean> {
  const { results } = await db
    .prepare('SELECT r.reported_at, r.foreground, r.charging, r.battery_percent, r.thermal, r.model_id FROM device_readiness r JOIN devices d ON d.id = r.device_id WHERE d.revoked_at IS NULL')
    .all<{ reported_at: number; foreground: number; charging: number; battery_percent: number; thermal: Thermal; model_id: string | null }>();
  return results.some((r) =>
    localReady({ reportedAt: r.reported_at, foreground: r.foreground === 1, charging: r.charging === 1, batteryPercent: r.battery_percent, thermal: r.thermal, modelId: r.model_id } satisfies Readiness, task, now),
  );
}

/**
 * Model triage (lld.md#inference). Routes deterministically, then either queues the work for a phone or reserves
 * cloud budget and dispatches. `dispatched_at` is written before I/O; a crash or timeout after it leaves an
 * uncertain attempt that keeps its reserved exposure and is never replayed automatically.
 */
export async function inferJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  if (!job.sourceId) return { kind: 'fail', errorCode: 'missing_source' };
  const db = env.CONTROL_DB;

  // Earlier runs of this job: a dispatched attempt became uncertain; an undispatched reservation is released.
  const { results: prior } = await db.prepare('SELECT id, route, state, reservation_json FROM inference_attempts WHERE job_id = ? ORDER BY created_at, rowid').bind(job.id).all<AttemptRow>();
  const dispatched = prior.filter((a) => a.route === 'cloud' && a.state === 'dispatched');
  if (dispatched.length > 0) {
    return {
      kind: 'fail',
      errorCode: 'dispatch_uncertain',
      statements: dispatched.map((a) =>
        db.prepare(`UPDATE inference_attempts SET state = 'uncertain', error_code = 'interrupted', completed_at = ? WHERE id = ? AND state = 'dispatched' AND ${CLAIM_GUARD}`).bind(now, a.id, ...guardParams(job)),
      ),
    };
  }
  for (const a of prior.filter((p) => p.state === 'reserved')) await releaseAttempt(db, a.id, JSON.parse(a.reservation_json) as Reservation, now, 'interrupted');
  if (prior.some((a) => ['queued', 'leased', 'succeeded', 'uncertain'].includes(a.state))) return { kind: 'complete', output: { skipped: 'already_routed' } };

  const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(job.sourceId).first<RegistryRow>();
  if (!source || source.revision !== job.inputRevision) return { kind: 'complete', output: { skipped: 'superseded' } };
  const settings = await loadSettings(db);
  const decision = route({
    mode: settings.inference.mode,
    sources: [{ privacy: source.privacy as Privacy, admission: source.admission, state: source.state }],
    task: 'background',
    localReady: await anyDeviceReady(db, now),
    cloud: cloudGate(settings.inference.cloud, env.AI !== undefined),
  });
  if (decision.route === 'blocked') return { kind: 'complete', output: { route: 'blocked', reason: decision.reason } };

  const loaded = await loadBodyChunks(env, source, decision.route === 'cloud' ? CLOUD_MAX_CHUNKS : MAX_DEVICE_CHUNKS);
  if (!loaded) return { kind: 'retry', errorCode: 'archive_unavailable' };
  const digest = await inputDigest(source, INFER_TASK, loaded.chunks);
  const queueLocal = (reason: string, repair = false): JobResult => ({
    kind: 'complete',
    output: { route: 'local', reason },
    statements: [queueLocalStatement(db, { id: crypto.randomUUID(), jobId: job.id, source, policyRevision: settings.policyRevision, digest, reason, repair }, now, { sql: CLAIM_GUARD, params: guardParams(job) })],
  });
  if (decision.route !== 'cloud') return queueLocal(decision.reason);
  return cloudTriage(job, env, now, { source, settings, digest, subject: loaded.subject, chunks: loaded.chunks, queueLocal });
}

async function releaseAttempt(db: D1Database, id: string, reservation: Reservation, now: number, code: string): Promise<void> {
  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND state = 'reserved' AND dispatched_at IS NULL)", id),
      db.prepare("UPDATE inference_attempts SET state = 'released', error_code = ?, completed_at = ? WHERE id = ?").bind(code, now, id),
      ...releaseStatements(db, reservation),
    ]);
  } catch (error) {
    if (!isAssertionFailure(error)) throw error;
  }
}

function actualNeurons(settings: Settings, usage: { inputTokens: number; outputTokens: number } | null): number | null {
  if (!usage) return null;
  const cloud = settings.inference.cloud;
  return Math.ceil((usage.inputTokens * (cloud.neuronsPerMillionInputTokens ?? 0) + usage.outputTokens * (cloud.neuronsPerMillionOutputTokens ?? 0)) / 1_000_000);
}

export interface CloudTask<T> {
  task: string;
  usageKind: UsageKind;
  digest: string;
  /** Recorded on the attempt for single-source tasks. */
  source: { id: string; revision: number } | null;
  chunkIds: string[] | null;
  /** Policy guards rechecked in the transaction that records dispatch. */
  eligible: { sql: string; params: unknown[] }[];
  messages: Message[];
  /** Chunk IDs the reply may cite, with their text lengths, for the schema-repair hint. */
  citable: { id: string; length: number }[];
  validate: (text: string) => { ok: true; value: T } | { ok: false; errors: string[] };
  /** Writes that store an accepted output; the last one's effect reports whether the output was kept. */
  store: (attemptId: string, modelId: string, value: T) => Promise<D1PreparedStatement[]>;
}

export type CloudOutcome =
  | { kind: 'succeeded'; attemptId: string; kept: boolean }
  | { kind: 'invalid'; errors: string[] }
  | { kind: 'budget_exhausted'; repair: boolean; errors: string[] }
  | { kind: 'busy' }
  | { kind: 'uncertain' }
  | { kind: 'policy_changed' };

/**
 * Reserve, record dispatch, call the model and validate, with one separately reserved schema repair. Each
 * attempt's usage is settled in its own batch, so a repair never hides the first request's spend.
 */
export async function dispatchCloud<T>(job: ClaimedJob, env: Env, now: number, settings: Settings, t: CloudTask<T>): Promise<CloudOutcome> {
  const db = env.CONTROL_DB;
  const modelId = settings.inference.cloud.modelId!;
  const ai = env.AI as unknown as TextModel;
  const running = { sql: RUNNING_GUARD, params: guardParams(job) };
  let messages: Message[] = t.messages;
  let lastErrors: string[] = [];

  for (const repair of [false, true]) {
    const attemptId = crypto.randomUUID();
    const reserved = await reserve(db, settings.inference, t.usageKind, { input: worstCaseInputTokens(messages), output: CLOUD_OUTPUT_TOKENS }, now, (reservation) => [
      assertion(db, running.sql, ...running.params),
      db.prepare(
        `INSERT INTO inference_attempts(id, job_id, route, model_id, policy_revision, input_digest, state, reservation_json, task, usage_kind, source_id, source_revision, reason, repair, chunk_ids_json, created_at)
         VALUES (?, ?, 'cloud', ?, ?, ?, 'reserved', ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      ).bind(
        attemptId,
        job.id,
        modelId,
        settings.policyRevision,
        t.digest,
        JSON.stringify(reservation),
        t.task,
        t.usageKind,
        t.source?.id ?? null,
        t.source?.revision ?? null,
        repair ? 'schema_repair' : 'cloud_allowed',
        repair ? 1 : 0,
        t.chunkIds ? JSON.stringify(t.chunkIds) : null,
        now,
      ),
    ]);
    if (!reserved.ok) {
      if (reserved.reason === 'cloud_busy') return { kind: 'busy' };
      return { kind: 'budget_exhausted', repair, errors: lastErrors };
    }
    const { reservation } = reserved;

    // Recheck policy in the same transaction that records dispatch, immediately before any I/O.
    try {
      await db.batch([
        assertion(db, running.sql, ...running.params),
        ...t.eligible.map((g) => assertion(db, g.sql, ...g.params)),
        assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND state = 'reserved')", attemptId),
        db.prepare("UPDATE inference_attempts SET state = 'dispatched', dispatched_at = ? WHERE id = ?").bind(now, attemptId),
      ]);
    } catch (error) {
      if (!isAssertionFailure(error)) throw error;
      await releaseAttempt(db, attemptId, reservation, now, 'policy_changed');
      return { kind: 'policy_changed' };
    }

    let reply: Awaited<ReturnType<typeof runCloud>>;
    try {
      reply = await runCloud(ai, modelId, messages);
    } catch (error) {
      // The provider may have processed and billed the request: keep the exposure and never redispatch.
      const code = error instanceof Error && error.message === 'timeout' ? 'timeout' : 'provider_error';
      await db.prepare("UPDATE inference_attempts SET state = 'uncertain', error_code = ?, completed_at = ? WHERE id = ? AND state = 'dispatched'").bind(code, now, attemptId).run();
      return { kind: 'uncertain' };
    }

    const neurons = actualNeurons(settings, reply.usage);
    const usage = JSON.stringify({ inputTokens: reply.usage?.inputTokens ?? null, outputTokens: reply.usage?.outputTokens ?? null, neurons: neurons ?? reservation.neurons, measured: neurons !== null });
    const checked = t.validate(reply.text);
    const settle = [assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND state = 'dispatched')", attemptId), ...settleStatements(db, reservation, neurons)];
    if (checked.ok) {
      const stored = await t.store(attemptId, modelId, checked.value);
      const results = await db.batch([
        ...settle,
        db.prepare("UPDATE inference_attempts SET state = 'succeeded', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attemptId),
        ...stored,
      ]);
      return { kind: 'succeeded', attemptId, kept: stored.length === 0 || (results[results.length - 1]?.meta.changes ?? 0) >= 1 };
    }
    lastErrors = checked.errors;
    await db.batch([
      ...settle,
      db.prepare("UPDATE inference_attempts SET state = 'invalid_output', error_code = 'invalid_output', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attemptId),
    ]);
    // One schema-repair attempt, separately reserved; the rejected text goes back only to the same model.
    messages = repairMessages(t.messages, reply.text, checked.errors, t.citable);
  }
  return { kind: 'invalid', errors: lastErrors };
}

async function cloudTriage(
  job: ClaimedJob,
  env: Env,
  now: number,
  ctx: { source: RegistryRow; settings: Settings; digest: string; subject: string; chunks: BodyChunk[]; queueLocal: (reason: string) => JobResult },
): Promise<JobResult> {
  const { source, settings } = ctx;
  const chunkLengths = new Map(ctx.chunks.map((c) => [c.id, c.text.length]));
  const chunkTexts = new Map(ctx.chunks.map((c) => [c.id, c.text]));
  const services = await knownServiceIds(env.CONTROL_DB);
  const outcome = await dispatchCloud(job, env, now, settings, {
    task: INFER_TASK,
    usageKind: 'automatic',
    digest: ctx.digest,
    source: { id: source.id, revision: source.revision },
    chunkIds: null,
    eligible: [sourceStillEligible(source.id, source.revision, source.privacy, settings.policyRevision)],
    messages: triageMessages(ctx.subject, ctx.chunks),
    citable: ctx.chunks.map((c) => ({ id: c.id, length: c.text.length })),
    validate: (text) => {
      const parsed = parseModelJson(text);
      if (!parsed.ok) return parsed;
      const quoted = resolveTriageQuotes(normalizeTriage(parsed.value), chunkTexts);
      return quoted.errors.length > 0 ? { ok: false as const, errors: quoted.errors } : validateTriage(quoted.value, chunkLengths, services);
    },
    store: async (attemptId, modelId, value) => [await resultStatement(env.CONTROL_DB, { id: attemptId, jobId: job.id, route: 'cloud', modelId }, source, settings.policyRevision, value, now)],
  });
  switch (outcome.kind) {
    case 'succeeded':
      return { kind: 'complete', output: { route: 'cloud', state: outcome.kept ? 'succeeded' : 'discarded', attemptId: outcome.attemptId } };
    case 'busy':
      return { kind: 'retry', errorCode: 'cloud_busy' };
    case 'uncertain':
      return { kind: 'fail', errorCode: 'dispatch_uncertain' };
    case 'policy_changed':
      return { kind: 'complete', output: { skipped: 'policy_changed' } };
    case 'budget_exhausted':
      // Budget gone: the first request waits for a phone; a needed repair becomes a visible failure.
      if (!outcome.repair) return ctx.queueLocal('budget_exhausted');
      return { kind: 'complete', output: { route: 'cloud', state: 'failed', error: 'invalid_output', repair: 'budget_exhausted', errors: outcome.errors } };
    case 'invalid':
      return { kind: 'complete', output: { route: 'cloud', state: 'failed', error: 'invalid_output', errors: outcome.errors } };
  }
}
