import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { RESERVATION_TTL_MS, releaseExpiredReservations, reserve } from '../../src/inference/budget';
import { DEVICE_LEASE_MS } from '../../src/inference/device';
import { DEFAULT_INFERENCE, type InferenceSettings, loadSettings } from '../../src/inference/settings';
import { T0, api, enrollOwner, ingest, runPending } from './support';

let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-8000-${String((keyCount += 1)).padStart(12, '0')}`;
const db = () => env.CONTROL_DB;

const BODY = 'Invoice 4471 for synthetic hosting is due on October 13.';
const mail = (subject: string, body = BODY) =>
  ['From: billing@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, 'MIME-Version: 1.0', 'Content-Type: text/plain', '', body, ''].join('\r\n');

const CLOUD: InferenceSettings = {
  ...DEFAULT_INFERENCE,
  cloud: {
    modelId: '@cf/test/synthetic-model',
    neuronsPerMillionInputTokens: 10_000,
    neuronsPerMillionOutputTokens: 20_000,
    account: 'dedicated',
    includedNeuronsPerDay: 10_000,
    paidMonthlyCeilingUsd: 0,
    usdPerThousandNeurons: null,
  },
};

const valid = (sourceId: string, overrides: Record<string, unknown> = {}) =>
  JSON.stringify({
    summary: 'A synthetic hosting invoice is due on October 13.',
    category: 'billing',
    importance: 'important',
    uncertainty: ['Year not stated'],
    deadline: { text: 'due on October 13', ambiguous: true },
    serviceIds: [],
    evidence: [{ chunkId: `${sourceId}:body-v1:0`, start: 0, end: 12 }],
    suggestedActions: [],
    ...overrides,
  });

/** Stand-in for the Workers AI binding: scripted replies, recorded requests. */
function fakeAi(replies: (string | Error)[]) {
  const calls: { model: string; input: { messages: { role: string; content: string }[]; max_tokens: number } }[] = [];
  const ai = {
    async run(model: string, input: (typeof calls)[number]['input']) {
      calls.push({ model, input });
      const next = replies.shift();
      if (next === undefined) throw new Error('unexpected call');
      if (next instanceof Error) throw next;
      return { response: next, usage: { prompt_tokens: 500, completion_tokens: 100 } };
    },
  };
  return { ai: ai as unknown as Ai, calls };
}

async function setInference(inference: InferenceSettings) {
  const current = await loadSettings(db());
  const res = await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: current.revision, inference } });
  expect(res.status).toBe(200);
  return res.body.data;
}

/** Ingest and run deterministic triage, leaving the infer job pending. */
async function triaged(subject: string, now = T0, body = BODY): Promise<string> {
  const id = await ingest(mail(subject, body), { now });
  await runPending('triage', now);
  return id;
}

const status = async (sourceId: string) => (await api('GET', `/sources/${sourceId}/inference`, { token })).body.data;
const periods = async () => (await api('GET', '/inference/budget', { token })).body.data.periods as { id: string; limit: any; reserved: any; settled: any }[];

const READY = { foreground: false, charging: true, batteryPercent: 80, thermal: 'none', modelId: 'local-test-model', modelManifestHash: 'a'.repeat(64) };
const claim = (now = T0, readiness: Record<string, unknown> = READY) => api('POST', '/device-work/claim', { token, key: key(), now, body: readiness });
const submit = (attemptId: string, body: Record<string, unknown>, now = T0) => api('POST', `/device-work/${attemptId}/result`, { token, key: key(), now, body });

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('routing', () => {
  it('queues for the phone with a visible reason when cloud is not bound or not configured', async () => {
    const id = await triaged('Invoice');
    expect(await runPending('infer')).toEqual(['complete']);
    expect(await status(id)).toMatchObject({ state: 'queued', reason: 'cloud_unavailable', result: null });

    const { ai, calls } = fakeAi([]);
    const other = await triaged('Second', T0 + 1);
    await runPending('infer', T0 + 1, { AI: ai });
    expect(await status(other)).toMatchObject({ state: 'queued', reason: 'model_rate_unknown' });
    await setInference({ ...CLOUD, cloud: { ...CLOUD.cloud, account: 'unattested' } });
    const third = await triaged('Third', T0 + 2);
    await runPending('infer', T0 + 2, { AI: ai });
    expect(await status(third)).toMatchObject({ state: 'queued', reason: 'account_headroom_unknown' });
    expect(calls).toEqual([]);
  });

  it('never sends local-only or no-AI sources to the cloud model', async () => {
    await setInference(CLOUD);
    const { ai, calls } = fakeAi([]);
    const localOnly = await triaged('Local only');
    const noAi = await triaged('No AI', T0 + 1);
    await db().prepare("UPDATE source_registry SET privacy = 'local_only' WHERE id = ?").bind(localOnly).run();
    await db().prepare("UPDATE source_registry SET privacy = 'no_ai' WHERE id = ?").bind(noAi).run();
    await runPending('infer', T0 + 1, { AI: ai });
    expect(calls).toEqual([]);
    expect(await status(localOnly)).toMatchObject({ state: 'queued', reason: 'local_only' });
    expect(await status(noAi)).toMatchObject({ state: 'blocked', reason: 'no_ai', attempts: [] });

    // The global mode applies on top of per-source privacy.
    await setInference({ ...CLOUD, mode: 'no_ai' });
    const blocked = await triaged('Global no AI', T0 + 2);
    await runPending('infer', T0 + 2, { AI: ai });
    expect(await status(blocked)).toMatchObject({ state: 'blocked', reason: 'no_ai' });
    expect(calls).toEqual([]);
  });

  it('prefers a ready charging phone over cloud for background work', async () => {
    await setInference(CLOUD);
    await api('POST', '/device-work/readiness', { token, key: key(), body: READY });
    const { ai, calls } = fakeAi([]);
    const id = await triaged('Invoice');
    await runPending('infer', T0, { AI: ai });
    expect(calls).toEqual([]);
    expect(await status(id)).toMatchObject({ state: 'queued', reason: 'local_ready' });
  });
});

describe('cloud dispatch', () => {
  it('stores validated output and records reason, model, policy revision and usage', async () => {
    const settings = await setInference(CLOUD);
    const id = await triaged('Invoice');
    const { ai, calls } = fakeAi([valid(id)]);
    expect(await runPending('infer', T0, { AI: ai })).toEqual(['complete']);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.model).toBe('@cf/test/synthetic-model');
    expect(calls[0]!.input.max_tokens).toBe(1000);
    expect(calls[0]!.input.messages[1]!.content).toContain(`<chunk id="${id}:body-v1:0">`);

    const view = await status(id);
    expect(view).toMatchObject({ state: 'succeeded', result: { category: 'billing', importance: 'important' }, provenance: { route: 'cloud', modelId: '@cf/test/synthetic-model' } });
    expect(view.attempts).toEqual([
      expect.objectContaining({ route: 'cloud', state: 'succeeded', reason: 'cloud_allowed', modelId: '@cf/test/synthetic-model', policyRevision: settings.revision, usage: { inputTokens: 500, outputTokens: 100, neurons: 7, measured: true } }),
    ]);
    const counters = Object.fromEntries((await periods()).map((p) => [p.id.split(':')[0] + ':' + p.id.split(':')[2], p]));
    expect(counters['requests:automatic']).toMatchObject({ limit: { requests: 100 }, reserved: { requests: 0, neurons: 0 }, settled: { requests: 1, neurons: 0 } });
    expect(counters['neurons:included']).toMatchObject({ limit: { neurons: 10_000 }, reserved: { requests: 0, neurons: 0 }, settled: { neurons: 7 } });
  });

  it('translates a critical suggestion to urgent', async () => {
    await setInference(CLOUD);
    const id = await triaged('Invoice');
    await runPending('infer', T0, { AI: fakeAi([valid(id, { importance: 'critical' })]).ai });
    expect((await status(id)).result.importance).toBe('urgent');
  });

  it('makes one separately reserved repair attempt, then fails visibly', async () => {
    await setInference(CLOUD);
    const repaired = await triaged('Repaired');
    const first = fakeAi([valid(repaired, { evidence: [{ chunkId: `${repaired}:body-v1:0`, start: 0, end: 5000 }] }), valid(repaired)]);
    await runPending('infer', T0, { AI: first.ai });
    expect(first.calls[1]!.input.messages.at(-1)!.content).toContain('$.evidence[0]:outside_chunk');
    expect((await status(repaired)).attempts.map((a: { state: string; repair: boolean }) => [a.state, a.repair])).toEqual([
      ['invalid_output', false],
      ['succeeded', true],
    ]);

    const failed = await triaged('Failed', T0 + 1);
    await runPending('infer', T0 + 1, { AI: fakeAi(['not json', valid(failed, { serviceIds: ['1d2c3b4a-5e6f-4a7b-8c9d-0e1f2a3b4c5d'] })]).ai });
    const view = await status(failed);
    expect(view).toMatchObject({ state: 'failed', result: null });
    expect(view.attempts.map((a: { errorCode: string }) => a.errorCode)).toEqual(['invalid_output', 'invalid_output']);
    const requests = (await periods()).find((p) => p.id.startsWith('requests:'))!;
    expect(requests.settled.requests).toBe(4);
  });

  it('records a provider timeout as uncertain, keeps its exposure and never redispatches', async () => {
    await setInference(CLOUD);
    const id = await triaged('Invoice');
    const { ai, calls } = fakeAi([new Error('timeout'), valid(id)]);
    expect(await runPending('infer', T0, { AI: ai })).toEqual(['failed']);
    expect(calls).toHaveLength(1);
    const [attempt] = (await status(id)).attempts;
    expect(attempt).toMatchObject({ state: 'uncertain', errorCode: 'timeout' });
    const neurons = (await periods()).find((p) => p.id.startsWith('neurons:'))!;
    expect(neurons.reserved.neurons).toBe(attempt.reservedNeurons);
    expect(await runPending('infer', T0 + 3_600_000, { AI: ai })).toEqual([]);

    const reconciled = await api('POST', `/inference/attempts/${attempt.id}/reconcile`, { token, key: key(), body: { outcome: 'charged' } });
    expect(reconciled.body.data.state).toBe('reconciled');
    const after = (await periods()).find((p) => p.id.startsWith('neurons:'))!;
    expect(after).toMatchObject({ reserved: { neurons: 0 }, settled: { neurons: attempt.reservedNeurons } });
    expect((await api('POST', `/inference/attempts/${attempt.id}/reconcile`, { token, key: key(), body: { outcome: 'charged' } })).status).toBe(409);
  });

  it('turns an interrupted dispatch into an uncertain attempt on the next run', async () => {
    await setInference(CLOUD);
    const id = await triaged('Invoice');
    const job = await db().prepare("SELECT id FROM jobs WHERE kind = 'infer'").first<{ id: string }>();
    await db()
      .prepare("INSERT INTO inference_attempts(id, job_id, route, model_id, policy_revision, input_digest, state, reservation_json, source_id, source_revision, dispatched_at, created_at) SELECT ?, ?, 'cloud', 'm', 1, 'd', 'dispatched', '{}', id, revision, ?, ? FROM source_registry WHERE id = ?")
      .bind(crypto.randomUUID(), job!.id, T0, T0, id)
      .run();
    const { ai, calls } = fakeAi([valid(id)]);
    expect(await runPending('infer', T0, { AI: ai })).toEqual(['failed']);
    expect(calls).toEqual([]);
    expect((await status(id)).attempts[0]).toMatchObject({ state: 'uncertain', errorCode: 'interrupted' });
  });
});

describe('budgets', () => {
  it('stops cloud at the automatic request allowance and queues the rest for the phone', async () => {
    await setInference({ ...CLOUD, limits: { automaticPerDay: 1, interactivePerMonth: 1000 } });
    const a = await triaged('A');
    const b = await triaged('B', T0 + 1);
    const { ai, calls } = fakeAi([valid(a)]);
    await runPending('infer', T0 + 1, { AI: ai });
    expect(calls).toHaveLength(1);
    expect(await status(a)).toMatchObject({ state: 'succeeded' });
    expect(await status(b)).toMatchObject({ state: 'queued', reason: 'budget_exhausted' });
  });

  it('uses the paid ceiling only after the included allocation, and never without one', async () => {
    const tiny = { ...CLOUD, cloud: { ...CLOUD.cloud, includedNeuronsPerDay: 5 } };
    await setInference(tiny);
    const a = await triaged('A');
    await runPending('infer', T0, { AI: fakeAi([]).ai });
    expect(await status(a)).toMatchObject({ state: 'queued', reason: 'budget_exhausted' });

    await setInference({ ...tiny, cloud: { ...tiny.cloud, paidMonthlyCeilingUsd: 1, usdPerThousandNeurons: 0.011 } });
    const b = await triaged('B', T0 + 1);
    await runPending('infer', T0 + 1, { AI: fakeAi([valid(b)]).ai });
    expect(await status(b)).toMatchObject({ state: 'succeeded' });
    const paid = (await periods()).find((p) => p.id.endsWith(':paid'))!;
    expect(paid).toMatchObject({ limit: { neurons: 90_909 }, settled: { neurons: 7 } });
  });

  it('lets only one of two concurrent reservations through', async () => {
    const settings = { ...CLOUD, limits: { automaticPerDay: 1, interactivePerMonth: 1000 } };
    const job = crypto.randomUUID();
    await db().prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES (?, 'x', 'infer', 'running', 0, '{}', 0)").bind(job).run();
    const attempt = (id: string) => (r: object) => [
      db().prepare("INSERT INTO inference_attempts(id, job_id, route, model_id, policy_revision, input_digest, state, reservation_json, created_at) VALUES (?, ?, 'cloud', 'm', 0, 'd', 'reserved', ?, ?)").bind(id, job, JSON.stringify(r), T0),
    ];
    const outcomes = await Promise.all([
      reserve(db(), settings, 'automatic', { input: 100, output: 100 }, T0, attempt(crypto.randomUUID())),
      reserve(db(), settings, 'automatic', { input: 100, output: 100 }, T0, attempt(crypto.randomUUID())),
    ]);
    expect(outcomes.filter((o) => o.ok)).toHaveLength(1);
    expect((await periods()).find((p) => p.id.startsWith('requests:'))!.reserved.requests).toBe(1);

    // An undispatched reservation expires and is released; nothing is settled.
    expect(await releaseExpiredReservations(db(), T0 + RESERVATION_TTL_MS)).toEqual({ released: 1 });
    expect((await periods()).find((p) => p.id.startsWith('requests:'))).toMatchObject({ reserved: { requests: 0 }, settled: { requests: 0 } });
  });
});

describe('device work', () => {
  async function queued(subject: string, now = T0) {
    const id = await triaged(subject, now);
    await runPending('infer', now);
    return id;
  }

  it('leases work to a ready phone and accepts one validated result under the lease', async () => {
    const id = await queued('Invoice');
    expect((await claim(T0, { ...READY, batteryPercent: 20 })).body.data).toEqual({ work: null, reason: 'not_ready' });
    expect((await claim(T0, { ...READY, thermal: 'severe' })).body.data).toEqual({ work: null, reason: 'not_ready' });
    const { work } = (await claim()).body.data;
    expect(work).toMatchObject({ sourceId: id, task: 'triage', chunkIds: [`${id}:body-v1:0`], modelId: 'local-test-model', policyRevision: 0 });
    expect((await claim()).body.data).toEqual({ work: null, reason: 'busy' });
    expect((await api('POST', `/device-work/${work.attemptId}/heartbeat`, { token, key: key(), body: { leaseToken: work.leaseToken } })).status).toBe(200);

    const body = { leaseToken: work.leaseToken, inputDigest: work.inputDigest, policyRevision: 0, outputJson: valid(id), modelManifestHash: 'a'.repeat(64) };
    expect((await submit(work.attemptId, { ...body, inputDigest: 'b'.repeat(64) })).body.error?.code).toBe('input_changed');
    expect((await submit(work.attemptId, body)).body.data).toEqual({ attemptId: work.attemptId, state: 'succeeded' });
    expect(await status(id)).toMatchObject({ state: 'succeeded', provenance: { route: 'local', modelId: 'local-test-model' } });
    // A duplicate agent with the same token cannot commit a second result.
    expect((await submit(work.attemptId, body)).body.error?.code).toBe('lease_lost');
  });

  it('refuses a late result after the lease expired and was reclaimed', async () => {
    const id = await queued('Invoice');
    const first = (await claim()).body.data.work;
    const later = T0 + DEVICE_LEASE_MS + 1;
    const second = (await claim(later)).body.data.work;
    expect(second.attemptId).toBe(first.attemptId);
    expect(second.leaseToken).not.toBe(first.leaseToken);
    const result = (lease: string) => ({ leaseToken: lease, inputDigest: first.inputDigest, policyRevision: 0, outputJson: valid(id), modelManifestHash: 'a'.repeat(64) });
    expect((await submit(first.attemptId, result(first.leaseToken), later)).body.error?.code).toBe('lease_lost');
    expect((await submit(first.attemptId, result(second.leaseToken), later)).body.data.state).toBe('succeeded');
  });

  it('queues one repair for invalid phone output and rejects results under an old policy', async () => {
    const id = await queued('Invoice');
    const work = (await claim()).body.data.work;
    const result = (w: typeof work, outputJson: string) => ({ leaseToken: w.leaseToken, inputDigest: w.inputDigest, policyRevision: w.policyRevision, outputJson, modelManifestHash: 'a'.repeat(64) });
    expect((await submit(work.attemptId, result(work, '{"summary":"x"}'))).body.data).toMatchObject({ state: 'invalid_output', repairQueued: true });
    const repair = (await claim()).body.data.work;
    expect(repair.attemptId).not.toBe(work.attemptId);

    await setInference({ ...DEFAULT_INFERENCE, mode: 'local_only' });
    expect((await submit(repair.attemptId, result(repair, valid(id)))).body.error?.code).toBe('policy_changed');
    const reclaimed = (await claim(T0 + DEVICE_LEASE_MS + 1)).body.data.work;
    expect(reclaimed.policyRevision).toBe(1);
    expect((await submit(reclaimed.attemptId, result(reclaimed, 'nope'), T0 + DEVICE_LEASE_MS + 1)).body.data).toMatchObject({ state: 'invalid_output', repairQueued: false });
    expect((await claim(T0 + DEVICE_LEASE_MS + 2)).body.data).toEqual({ work: null, reason: 'no_work' });
    expect(await status(id)).toMatchObject({ state: 'failed', result: null });
  });

  it('withdraws queued work when the source is deleted or loses AI permission', async () => {
    const deleted = await queued('Deleted');
    const restricted = await queued('Restricted', T0 + 1);
    const review = (await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [deleted] } } })).body.data;
    await api('POST', `/reviews/${review.reviewId}/confirm`, { token, key: key(), body: { paramsDigest: review.paramsDigest } });
    await db().prepare("UPDATE source_registry SET privacy = 'no_ai' WHERE id = ?").bind(restricted).run();
    expect((await claim(T0 + 2)).body.data).toEqual({ work: null, reason: 'no_work' });
    const states = await db().prepare('SELECT state, error_code FROM inference_attempts ORDER BY created_at, rowid').all();
    expect(states.results).toEqual([
      { state: 'cancelled', error_code: 'deleted' },
      { state: 'cancelled', error_code: 'not_eligible' },
    ]);
  });
});

describe('settings', () => {
  it('validates limits the owner may only lower and requires the current revision', async () => {
    const raise = await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: 0, inference: { ...DEFAULT_INFERENCE, limits: { automaticPerDay: 101, interactivePerMonth: 1000 } } } });
    expect(raise.status).toBe(400);
    expect((await api('GET', '/settings', { token })).body.data).toEqual({ revision: 0, inference: DEFAULT_INFERENCE });
    await setInference(CLOUD);
    const stale = await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: 0, inference: CLOUD } });
    expect(stale.body.error?.code).toBe('stale_revision');
    expect((await api('GET', '/inference/budget', { token })).body.data).toMatchObject({ policyRevision: 1, cloudBound: false, scope: 'application' });
  });
});
