import type { AuthContext } from '../auth/sessions';
import { CHAT_TASK } from '../chat/job';
import { chatDeviceTask } from '../chat/device';
import { assertion, isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { v } from '../http/validate';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow } from '../sources/registry';
import { INFER_TASK, MAX_DEVICE_CHUNKS, inputDigest, knownServiceIds, loadBodyChunks, queueLocalStatement, resultStatement } from './job';
import { THERMAL_STATES, type Readiness, localReady } from './routing';
import { type Message, triageMessages } from './cloud';
import { loadSettings } from './settings';
import { resolveTriageQuotes } from './quotes';
import { normalizeTriage, parseModelJson, validateTriage } from './triage-output';

/** Device leases are replay-safe: an expired lease is reclaimed with a new token and a late result is refused. */
export const DEVICE_LEASE_MS = 5 * 60 * 1000;
const MAX_OUTPUT_CHARS = 64 * 1024;

const readinessShape = {
  foreground: v.boolean(),
  charging: v.boolean(),
  batteryPercent: v.integer({ min: 0, max: 100 }),
  thermal: v.oneOf(THERMAL_STATES),
  /** Model ID from the pinned manifest the phone verified; null when no model is loaded. */
  modelId: v.nullable(v.string({ max: 100, pattern: /^[A-Za-z0-9._:/-]+$/ })),
  modelManifestHash: v.nullable(v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ })),
};

export const readinessBody = v.object(readinessShape);

async function recordReadiness(db: D1Database, deviceId: string, body: ReturnType<typeof readinessBody>, now: number): Promise<Readiness> {
  await db
    .prepare(
      `INSERT INTO device_readiness(device_id, reported_at, foreground, charging, battery_percent, thermal, model_id, manifest_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET reported_at = excluded.reported_at, foreground = excluded.foreground, charging = excluded.charging,
         battery_percent = excluded.battery_percent, thermal = excluded.thermal, model_id = excluded.model_id, manifest_hash = excluded.manifest_hash`,
    )
    .bind(deviceId, now, body.foreground ? 1 : 0, body.charging ? 1 : 0, body.batteryPercent, body.thermal, body.modelId, body.modelManifestHash)
    .run();
  return { reportedAt: now, foreground: body.foreground, charging: body.charging, batteryPercent: body.batteryPercent, thermal: body.thermal, modelId: body.modelId };
}

// POST /device-work/readiness — OS-reported capability; routing prefers a ready phone over cloud.
export async function reportReadiness(env: Env, now: number, body: ReturnType<typeof readinessBody>, auth: AuthContext) {
  const report = await recordReadiness(env.CONTROL_DB, auth.deviceId, body, now);
  return { ready: localReady(report, 'background', now) };
}

export interface AttemptRow {
  id: string;
  job_id: string;
  model_id: string;
  source_id: string | null;
  source_revision: number | null;
  policy_revision: number;
  input_digest: string;
  task: string;
  usage_kind: 'automatic' | 'interactive';
  repair: number;
  chunk_ids_json: string | null;
}

/** What one kind of device task needs: re-verification at claim, and validation and storage of a result. */
export interface DeviceTask {
  /** Rebuilds the input under current policy; null when the work is no longer allowed or the input changed. */
  verify(env: Env, row: AttemptRow, policyRevision: number): Promise<{ chunkIds: string[]; digest: string; messages: Message[]; extra: Record<string, unknown> } | null>;
  /** Validates output; on success returns the writes that store it (the last one's effect says whether it was kept). */
  accept(env: Env, row: AttemptRow, outputJson: string, policyRevision: number, now: number): Promise<{ ok: true; store: D1PreparedStatement[] } | { ok: false; errors: string[] }>;
  /** Writes when the output stays invalid after the repair, or the phone declines the input; [code] names why. */
  failed(db: D1Database, row: AttemptRow, now: number, code?: string): Promise<D1PreparedStatement[]>;
}

const triageTask: DeviceTask = {
  async verify(env, row) {
    const source = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
    const loaded = source ? await loadBodyChunks(env, source, MAX_DEVICE_CHUNKS) : null;
    if (!source || !loaded || source.revision !== row.source_revision || source.state !== 'ready' || source.admission !== 'normal' || !['hybrid', 'local_only'].includes(source.privacy)) return null;
    return {
      chunkIds: loaded.chunks.map((c) => c.id),
      digest: await inputDigest(source, row.task, loaded.chunks),
      messages: triageMessages(loaded.subject, loaded.chunks),
      extra: { sourceId: source.id, sourceRevision: source.revision },
    };
  },
  async accept(env, row, outputJson, policyRevision, now) {
    const db = env.CONTROL_DB;
    const source = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(row.source_id).first<RegistryRow>();
    const loaded = source ? await loadBodyChunks(env, source, MAX_DEVICE_CHUNKS) : null;
    if (!source || !loaded) throw leaseLost();
    const parsed = parseModelJson(outputJson);
    const quoted = parsed.ok ? resolveTriageQuotes(normalizeTriage(parsed.value), new Map(loaded.chunks.map((c) => [c.id, c.text]))) : null;
    const checked = !parsed.ok ? parsed : quoted!.errors.length > 0 ? { ok: false as const, errors: quoted!.errors } : validateTriage(quoted!.value, new Map(loaded.chunks.map((c) => [c.id, c.text.length])), await knownServiceIds(db));
    if (!checked.ok) return checked;
    return { ok: true, store: [await resultStatement(db, { id: row.id, jobId: row.job_id, route: 'local', modelId: row.model_id }, { id: source.id, revision: row.source_revision!, privacy: source.privacy }, policyRevision, checked.value, now)] };
  },
  async failed() {
    return [];
  },
};

const TASKS: Record<string, DeviceTask> = { [INFER_TASK]: triageTask, [CHAT_TASK]: chatDeviceTask };

const claimable = (tasks: string[]) =>
  `route = 'local' AND (state = 'queued' OR (state = 'leased' AND lease_until < ?)) AND task IN (${tasks.map((t) => `'${t}'`).join(', ')})`;

/**
 * POST /device-work/claim — records readiness, then leases the oldest queued local task the phone may run now:
 * any task while it is a charging background worker, chat only while it is merely in the foreground. One task
 * per device at a time. Work whose source or policy no longer allows it is cancelled here, not handed out.
 */
export async function claimWork(env: Env, now: number, body: ReturnType<typeof readinessBody>, auth: AuthContext) {
  const db = env.CONTROL_DB;
  const report = await recordReadiness(db, auth.deviceId, body, now);
  const background = localReady(report, 'background', now);
  if (!background && !localReady(report, 'interactive', now)) return { work: null, reason: 'not_ready' };
  const busy = await db.prepare("SELECT 1 AS hit FROM inference_attempts WHERE device_id = ? AND state = 'leased' AND lease_until >= ?").bind(auth.deviceId, now).first();
  if (busy) return { work: null, reason: 'busy' };
  const settings = await loadSettings(db);
  const condition = claimable(background ? Object.keys(TASKS) : [CHAT_TASK]);

  for (let i = 0; i < 10; i += 1) {
    const token = crypto.randomUUID();
    const row = await db
      .prepare(
        `UPDATE inference_attempts SET state = 'leased', lease_token = ?, lease_until = ?, device_id = ?, model_id = ?
         WHERE id = (SELECT id FROM inference_attempts WHERE ${condition} ORDER BY created_at, rowid LIMIT 1) AND ${condition}
         RETURNING id, job_id, model_id, source_id, source_revision, policy_revision, input_digest, task, usage_kind, repair, chunk_ids_json`,
      )
      .bind(token, now + DEVICE_LEASE_MS, auth.deviceId, body.modelId, now, now)
      .first<AttemptRow>();
    if (!row) return { work: null, reason: 'no_work' };

    const task = TASKS[row.task];
    const prepared = task && settings.inference.mode !== 'no_ai' ? await task.verify(env, row, settings.policyRevision) : null;
    if (!prepared || prepared.digest !== row.input_digest) {
      await db.prepare("UPDATE inference_attempts SET state = 'cancelled', error_code = 'not_eligible', completed_at = ? WHERE id = ? AND lease_token = ?").bind(now, row.id, token).run();
      continue;
    }
    // Policy changes that still allow local work move the task to the current policy revision.
    if (row.policy_revision !== settings.policyRevision) {
      await db.prepare('UPDATE inference_attempts SET policy_revision = ? WHERE id = ? AND lease_token = ?').bind(settings.policyRevision, row.id, token).run();
    }
    return {
      work: {
        attemptId: row.id,
        jobId: row.job_id,
        ...prepared.extra,
        policyRevision: settings.policyRevision,
        task: row.task,
        inputDigest: row.input_digest,
        chunkIds: prepared.chunkIds,
        // The exact messages the cloud adapter would send, so local and cloud answer the same prompt (lld.md#triage).
        messages: prepared.messages,
        repair: row.repair === 1,
        modelId: body.modelId,
        leaseToken: token,
        expiresAt: new Date(now + DEVICE_LEASE_MS).toISOString(),
      },
    };
  }
  return { work: null, reason: 'no_work' };
}

const leaseToken = v.uuid();
const leaseLost = () => new HttpError(409, 'lease_lost', 'This task is no longer assigned to this device.');

export const heartbeatBody = v.object({ leaseToken });

// POST /device-work/{attemptId}/heartbeat — extends only a live lease held by this device.
export async function heartbeat(env: Env, now: number, body: ReturnType<typeof heartbeatBody>, auth: AuthContext, attemptId: string) {
  const result = await env.CONTROL_DB.prepare("UPDATE inference_attempts SET lease_until = ? WHERE id = ? AND lease_token = ? AND device_id = ? AND state = 'leased' AND lease_until >= ?")
    .bind(now + DEVICE_LEASE_MS, attemptId, body.leaseToken, auth.deviceId, now)
    .run();
  if (result.meta.changes !== 1) throw leaseLost();
  return { attemptId, expiresAt: new Date(now + DEVICE_LEASE_MS).toISOString() };
}

export const resultBody = v.object({
  leaseToken,
  inputDigest: v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ }),
  policyRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }),
  outputJson: v.string({ max: MAX_OUTPUT_CHARS }),
  modelManifestHash: v.string({ min: 64, max: 64, pattern: /^[0-9a-f]{64}$/ }),
});

/**
 * POST /device-work/{attemptId}/result — accepted only under the live lease, for the same input and policy.
 * The output passes the same validation as cloud output. One invalid output queues one repair task.
 */
export async function submitResult(env: Env, now: number, body: ReturnType<typeof resultBody>, auth: AuthContext, attemptId: string) {
  const db = env.CONTROL_DB;
  const attempt = await db
    .prepare('SELECT id, job_id, model_id, source_id, source_revision, policy_revision, input_digest, task, usage_kind, repair, chunk_ids_json, lease_token, device_id, state, lease_until FROM inference_attempts WHERE id = ?')
    .bind(attemptId)
    .first<AttemptRow & { lease_token: string | null; device_id: string | null; state: string; lease_until: number | null }>();
  if (!attempt || attempt.state !== 'leased' || attempt.lease_token !== body.leaseToken || attempt.device_id !== auth.deviceId || (attempt.lease_until ?? 0) < now) throw leaseLost();
  if (body.inputDigest !== attempt.input_digest) throw new HttpError(409, 'input_changed', 'The source changed after this task was assigned.');
  const settings = await loadSettings(db);
  if (body.policyRevision !== attempt.policy_revision || body.policyRevision !== settings.policyRevision) throw new HttpError(409, 'policy_changed', 'Inference settings changed. Claim the task again.');
  const task = TASKS[attempt.task];
  if (!task) throw leaseLost();

  const checked = await task.accept(env, attempt, body.outputJson, settings.policyRevision, now);
  const live = assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND lease_token = ? AND state = 'leased' AND lease_until >= ?)", attempt.id, body.leaseToken, now);
  const usage = JSON.stringify({ modelManifestHash: body.modelManifestHash });

  try {
    if (checked.ok) {
      const results = await db.batch([
        live,
        db.prepare("UPDATE inference_attempts SET state = 'succeeded', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attempt.id),
        ...checked.store,
      ]);
      return { attemptId, state: (results.at(-1)?.meta.changes ?? 0) >= 1 ? 'succeeded' : 'discarded' };
    }
    const repair = attempt.repair === 0;
    await db.batch([
      live,
      db.prepare("UPDATE inference_attempts SET state = 'invalid_output', error_code = 'invalid_output', usage_json = ?, completed_at = ? WHERE id = ?").bind(usage, now, attempt.id),
      ...(repair
        ? [
            queueLocalStatement(
              db,
              {
                id: crypto.randomUUID(),
                jobId: attempt.job_id,
                task: attempt.task,
                usageKind: attempt.usage_kind,
                source: attempt.source_id ? { id: attempt.source_id, revision: attempt.source_revision! } : null,
                chunkIds: attempt.chunk_ids_json ? (JSON.parse(attempt.chunk_ids_json) as string[]) : null,
                policyRevision: settings.policyRevision,
                digest: attempt.input_digest,
                reason: 'schema_repair',
                repair: true,
              },
              now,
            ),
          ]
        : await task.failed(db, attempt, now)),
    ]);
    return { attemptId, state: 'invalid_output', errors: checked.errors, repairQueued: repair };
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    // The unique success index: another agent already committed the authoritative output for this job.
    if (error instanceof Error && /UNIQUE constraint failed/.test(error.message)) throw new HttpError(409, 'already_completed', 'Another result was already accepted for this task.');
    throw error;
  }
}

export const declineBody = v.object({ leaseToken, reason: v.oneOf(['input_too_large'] as const) });

/**
 * POST /device-work/{attemptId}/decline — the phone cannot run this input within its model's token limit. The task
 * ends visibly with that reason instead of being retried by the same phone or answered with invented output.
 */
export async function declineWork(env: Env, now: number, body: ReturnType<typeof declineBody>, auth: AuthContext, attemptId: string) {
  const db = env.CONTROL_DB;
  const attempt = await db
    .prepare('SELECT id, job_id, model_id, source_id, source_revision, policy_revision, input_digest, task, usage_kind, repair, chunk_ids_json FROM inference_attempts WHERE id = ? AND lease_token = ? AND device_id = ?')
    .bind(attemptId, body.leaseToken, auth.deviceId)
    .first<AttemptRow>();
  const task = attempt ? TASKS[attempt.task] : undefined;
  if (!attempt || !task) throw leaseLost();
  try {
    await db.batch([
      assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND lease_token = ? AND state = 'leased' AND lease_until >= ?)", attempt.id, body.leaseToken, now),
      db.prepare("UPDATE inference_attempts SET state = 'cancelled', reason = ?, error_code = ?, completed_at = ? WHERE id = ?").bind(body.reason, body.reason, now, attempt.id),
      ...(await task.failed(db, attempt, now, body.reason)),
    ]);
  } catch (error) {
    if (isAssertionFailure(error)) throw leaseLost();
    throw error;
  }
  return { attemptId, state: 'cancelled', reason: body.reason };
}
