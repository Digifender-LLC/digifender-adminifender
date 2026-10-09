import { describe, expect, it } from 'vitest';
import { estimateNeurons, paidNeuronLimit } from '../../src/inference/budget';
import { TimeoutError, triageMessages, withTimeout, worstCaseInputTokens } from '../../src/inference/cloud';
import { type Readiness, type RouteInput, READINESS_TTL_MS, cloudGate, localReady, route } from '../../src/inference/routing';
import { DEFAULT_INFERENCE } from '../../src/inference/settings';
import { normalizeTriage, parseModelJson, validateTriage } from '../../src/inference/triage-output';

const NOW = Date.UTC(2026, 9, 3, 12);
const report = (over: Partial<Readiness> = {}): Readiness => ({ reportedAt: NOW, foreground: false, charging: true, batteryPercent: 80, thermal: 'none', modelId: 'm', ...over });
const base: RouteInput = { mode: 'auto', sources: [{ privacy: 'hybrid', admission: 'normal', state: 'ready' }], task: 'background', localReady: false, cloud: { ok: true } };

describe('route', () => {
  it('applies the deterministic order', () => {
    expect(route(base)).toEqual({ route: 'cloud', reason: 'cloud_allowed' });
    expect(route({ ...base, localReady: true })).toEqual({ route: 'local', reason: 'local_ready' });
    expect(route({ ...base, cloud: { ok: false, reason: 'cloud_unavailable' } })).toEqual({ route: 'queued_local', reason: 'cloud_unavailable' });
    expect(route({ ...base, mode: 'local_only' })).toEqual({ route: 'queued_local', reason: 'local_only' });
    expect(route({ ...base, sources: [...base.sources, { privacy: 'local_only', admission: 'normal', state: 'ready' }] })).toEqual({ route: 'queued_local', reason: 'local_only' });
    expect(route({ ...base, mode: 'no_ai', localReady: true })).toEqual({ route: 'blocked', reason: 'no_ai' });
    expect(route({ ...base, sources: [{ privacy: 'no_ai', admission: 'normal', state: 'ready' }], localReady: true })).toEqual({ route: 'blocked', reason: 'no_ai' });
    expect(route({ ...base, sources: [{ privacy: 'restricted_metadata', admission: 'normal', state: 'ready' }] })).toEqual({ route: 'blocked', reason: 'restricted' });
    expect(route({ ...base, sources: [{ privacy: 'hybrid', admission: 'quarantine', state: 'ready' }] })).toEqual({ route: 'blocked', reason: 'quarantined' });
    expect(route({ ...base, sources: [{ privacy: 'hybrid', admission: 'normal', state: 'deleting' }] })).toEqual({ route: 'blocked', reason: 'deleted' });
  });

  it('gates local readiness on OS-reported battery, thermal, model and task mode', () => {
    expect(localReady(report(), 'background', NOW)).toBe(true);
    expect(localReady(report({ charging: false }), 'background', NOW)).toBe(false);
    expect(localReady(report({ charging: false, foreground: true }), 'interactive', NOW)).toBe(true);
    expect(localReady(report({ foreground: false }), 'interactive', NOW)).toBe(false);
    expect(localReady(report({ batteryPercent: 29 }), 'background', NOW)).toBe(false);
    expect(localReady(report({ batteryPercent: 30 }), 'background', NOW)).toBe(true);
    expect(localReady(report({ thermal: 'moderate' }), 'background', NOW)).toBe(true);
    expect(localReady(report({ thermal: 'severe' }), 'background', NOW)).toBe(false);
    expect(localReady(report({ modelId: null }), 'background', NOW)).toBe(false);
    expect(localReady(report({ reportedAt: NOW - READINESS_TTL_MS - 1 }), 'background', NOW)).toBe(false);
    expect(localReady(null, 'background', NOW)).toBe(false);
  });

  it('denies cloud when the binding, rate or account headroom is unknown', () => {
    const cloud = { ...DEFAULT_INFERENCE.cloud, modelId: '@cf/a/b', neuronsPerMillionInputTokens: 1, neuronsPerMillionOutputTokens: 1, account: 'dedicated' as const };
    expect(cloudGate(cloud, true)).toEqual({ ok: true });
    expect(cloudGate(cloud, false)).toEqual({ ok: false, reason: 'cloud_unavailable' });
    expect(cloudGate({ ...cloud, neuronsPerMillionOutputTokens: null }, true)).toEqual({ ok: false, reason: 'model_rate_unknown' });
    expect(cloudGate({ ...cloud, account: 'unattested' }, true)).toEqual({ ok: false, reason: 'account_headroom_unknown' });
    expect(cloudGate({ ...cloud, account: 'unattested', paidMonthlyCeilingUsd: 5, usdPerThousandNeurons: null }, true)).toEqual({ ok: false, reason: 'account_headroom_unknown' });
    expect(cloudGate({ ...cloud, account: 'unattested', paidMonthlyCeilingUsd: 5, usdPerThousandNeurons: 0.011 }, true)).toEqual({ ok: true });
  });
});

describe('budget arithmetic', () => {
  const cloud = { ...DEFAULT_INFERENCE.cloud, neuronsPerMillionInputTokens: 10_000, neuronsPerMillionOutputTokens: 20_000 };
  it('rounds worst-case neurons up and needs a rate', () => {
    expect(estimateNeurons(cloud, 1000, 1000)).toBe(30);
    expect(estimateNeurons(cloud, 1, 0)).toBe(1);
    expect(estimateNeurons({ ...cloud, neuronsPerMillionInputTokens: null }, 1, 0)).toBe(Number.POSITIVE_INFINITY);
  });
  it('buys no paid neurons by default', () => {
    expect(paidNeuronLimit(cloud)).toBe(0);
    expect(paidNeuronLimit({ ...cloud, paidMonthlyCeilingUsd: 5 })).toBe(0);
    expect(paidNeuronLimit({ ...cloud, paidMonthlyCeilingUsd: 5, usdPerThousandNeurons: 0.01 })).toBe(500_000);
  });
  it('bounds input tokens by UTF-8 bytes plus framing', () => {
    const messages = triageMessages('S', [{ id: 'c', text: 'é'.repeat(100) }]);
    const bytes = messages.reduce((n, m) => n + new TextEncoder().encode(m.content).byteLength, 0);
    expect(worstCaseInputTokens(messages)).toBe(bytes + 64 + 32);
  });
});

describe('model output', () => {
  const chunks = new Map([['s:body-v1:0', 40]]);
  const ok = { summary: 'x', category: 'other', importance: 'routine', uncertainty: [], deadline: null, serviceIds: [], evidence: [{ chunkId: 's:body-v1:0', start: 0, end: 40 }], suggestedActions: [] };
  it('checks evidence against the given chunks and services against the catalog', () => {
    expect(validateTriage(ok, chunks, new Set()).ok).toBe(true);
    expect(validateTriage({ ...ok, evidence: [{ chunkId: 's:body-v1:0', start: 0, end: 41 }] }, chunks, new Set())).toEqual({ ok: false, errors: ['$.evidence[0]:outside_chunk'] });
    expect(validateTriage({ ...ok, evidence: [{ chunkId: 's:body-v1:0', start: 5, end: 5 }] }, chunks, new Set())).toEqual({ ok: false, errors: ['$.evidence[0]:outside_chunk'] });
    expect(validateTriage({ ...ok, evidence: [{ chunkId: 'other:0', start: 0, end: 1 }] }, chunks, new Set())).toEqual({ ok: false, errors: ['$.evidence[0].chunkId:not_in_context'] });
    const service = '1d2c3b4a-5e6f-4a7b-8c9d-0e1f2a3b4c5d';
    expect(validateTriage({ ...ok, serviceIds: [service] }, chunks, new Set())).toEqual({ ok: false, errors: ['$.serviceIds[0]:unknown_service'] });
    expect(validateTriage({ ...ok, serviceIds: [service] }, chunks, new Set([service])).ok).toBe(true);
    expect(validateTriage({ ...ok, tool: 'run' }, chunks, new Set())).toEqual({ ok: false, errors: ['$.tool:unknown_field'] });
  });
  it('translates critical to urgent and tolerates one fenced block', () => {
    expect(normalizeTriage({ ...ok, importance: 'Critical' })).toMatchObject({ importance: 'urgent' });
    expect(parseModelJson('```json\n{"a":1}\n```')).toEqual({ ok: true, value: { a: 1 } });
    expect(parseModelJson('Sure! {"a":1}')).toEqual({ ok: false, errors: ['$:not_json'] });
  });
});

describe('withTimeout', () => {
  it('rejects a provider call that does not settle in time', async () => {
    await expect(withTimeout(new Promise(() => {}), 5)).rejects.toBeInstanceOf(TimeoutError);
    await expect(withTimeout(Promise.resolve(1), 1000)).resolves.toBe(1);
  });
});
