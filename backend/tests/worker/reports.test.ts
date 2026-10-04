import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { FUTURE_SKEW_MS, parseWrapper, validateReport } from '../../src/reports/verify';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const ON = { REPORTS_ENABLED: 'true', REPORT_KEY_WRAP: 'test-only-report-key-wrap-0123456789abcdef' };
const ALIAS = 'reports-hosting@ops.example';
const encoder = new TextEncoder();

let token: string;
let counter = 0;
const key = () => `00000000-0000-4000-8000-${String(++counter).padStart(12, '0')}`;
const on = (method: string, path: string, body?: unknown, now = T0) => api(method, path, { token, key: key(), body, now, env: { ...env, ...ON } });

const b64url = (bytes: Uint8Array) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const fromB64url = (s: string) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (s.length % 4)) % 4)), (c) => c.charCodeAt(0));

async function signed(secret: string, keyId: string, payload: object): Promise<string> {
  const bytes = encoder.encode(JSON.stringify(payload));
  const hmac = await crypto.subtle.importKey('raw', fromB64url(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('HMAC', hmac, bytes));
  return JSON.stringify({ schemaVersion: 1, keyId, payloadBase64url: b64url(bytes), signature: b64url(signature) });
}

const reportMail = (wrapper: string) =>
  [
    'From: whumpo@hosting.example',
    `To: ${ALIAS}`,
    'Subject: Whumpo report',
    `Message-ID: <${crypto.randomUUID()}@hosting.example>`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain; charset=utf-8',
    '',
    'Automated report.',
    '--b1',
    'Content-Type: application/json; name="adminifender-report.json"',
    'Content-Disposition: attachment; filename="adminifender-report.json"',
    'Content-Transfer-Encoding: base64',
    '',
    btoa(wrapper),
    '--b1--',
    '',
  ].join('\r\n');

let serviceId: string;
let keyId: string;
let secret: string;

const payload = (over: Record<string, unknown> = {}) => ({
  eventId: crypto.randomUUID(),
  serviceId,
  kind: 'maintenance',
  occurredAt: '2026-10-03T11:00:00Z',
  sequence: 17,
  concernKey: 'maintenance:health',
  classification: 'metadata_only',
  coverage: { scope: 'single_observation', status: 'complete', omittedCount: 0 },
  summary: 'Scheduled health work is overdue',
  facts: { job: 'health', state: 'overdue', oldestAgeSeconds: 7200 },
  links: [{ label: 'Recovery instructions', url: 'https://operator.example.invalid/recovery/health' }],
  ...over,
});

async function deliver(body: object, to = ALIAS, wrapperSecret = secret, wrapperKey = keyId): Promise<string> {
  const id = await ingest(reportMail(await signed(wrapperSecret, wrapperKey, body)), { to, env: ON });
  await runPending('report', T0, ON);
  return id;
}

const overview = async () => (await on('GET', '/operations/overview')).body.data;
const registry = (id: string) => env.CONTROL_DB.prepare('SELECT admission, privacy, privacy_floor FROM source_registry WHERE id = ?').bind(id).first<{ admission: string; privacy: string; privacy_floor: string }>();

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('signed reports are off unless enabled', () => {
  it('refuses P2 routes and treats report mail as ordinary mail', async () => {
    const res = await api('GET', '/services', { token });
    expect(res.status).toBe(403);
    expect(res.body.error?.code).toBe('feature_disabled');
    const id = await ingest(reportMail('{"schemaVersion":1}'), { to: ALIAS });
    expect(await registry(id)).toMatchObject({ admission: 'normal', privacy: 'hybrid' });
  });
});

describe('signed reports (P2, enabled)', () => {
  beforeEach(async () => {
    serviceId = (await on('POST', '/services', { name: 'Hosting', aliases: [ALIAS], targets: ['domain:example.com'] })).body.data.id;
    const created = await on('POST', `/services/${serviceId}/report-keys`, { alias: ALIAS });
    expect(created.status).toBe(201);
    keyId = created.body.data.keyId;
    secret = created.body.data.secretBase64url;
  });

  it('shows the key once and never stores or lists it in readable form', async () => {
    const listed = await on('GET', '/services');
    expect(JSON.stringify(listed.body)).not.toContain(secret);
    expect(listed.body.data.services[0].reportKeys).toEqual([expect.objectContaining({ id: keyId, alias: ALIAS, revoked: false })]);
    const stored = await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM mutations WHERE result_json LIKE ?').bind(`%${secret}%`).first<{ n: number }>();
    expect(stored?.n).toBe(0);
  });

  it('restricts report mail before anything else and stores a verified report as current', async () => {
    const id = await deliver(payload());
    expect(await registry(id)).toEqual({ admission: 'normal', privacy: 'restricted_metadata', privacy_floor: 'restricted_metadata' });
    const view = await overview();
    expect(view.services[0]).toMatchObject({ name: 'Hosting', freshness: 'current' });
    expect(view.services[0].reports).toEqual([
      expect.objectContaining({ kind: 'maintenance', concernKey: 'maintenance:health', sequence: 17, verifiedSignature: true, sourceIds: [id], coverage: { scope: 'single_observation', status: 'complete', omittedCount: 0 } }),
    ]);
    expect(view.verificationIssues).toEqual([]);
  });

  it('never sends report mail to a model, even with cloud inference available', async () => {
    const settings = (await on('GET', '/settings')).body.data;
    const cloud = { ...settings.inference, cloud: { modelId: '@cf/test/synthetic-model', neuronsPerMillionInputTokens: 10_000, neuronsPerMillionOutputTokens: 20_000, account: 'dedicated', includedNeuronsPerDay: 10_000, paidMonthlyCeilingUsd: 0, usdPerThousandNeurons: null } };
    expect((await on('PATCH', '/settings', { expectedRevision: settings.revision, inference: cloud })).status).toBe(200);
    const id = await deliver(payload());
    const calls: unknown[] = [];
    const ai = { run: async (...args: unknown[]) => (calls.push(args), { response: '{}' }) } as unknown as Ai;
    await runPending('triage', T0, { ...ON, AI: ai });
    await runPending('infer', T0, { ...ON, AI: ai });
    expect(calls).toEqual([]);
    expect((await on('GET', `/sources/${id}/inference`)).body.data).toMatchObject({ state: 'blocked', result: null });
  });

  it('quarantines a bad signature with one issue and leaves trusted state alone', async () => {
    const forged = await deliver(payload(), ALIAS, b64url(new Uint8Array(32)));
    expect(await registry(forged)).toMatchObject({ admission: 'quarantine', privacy: 'restricted_metadata' });
    const view = await overview();
    expect(view.services[0]).toMatchObject({ freshness: 'unobserved', reports: [] });
    expect(view.verificationIssues).toEqual([expect.objectContaining({ sourceId: forged, code: 'bad_signature' })]);
  });

  it('does not trust a valid signature delivered to another address', async () => {
    const id = await deliver(payload(), 'inbox@adminifender.example');
    expect((await overview()).verificationIssues).toEqual([expect.objectContaining({ sourceId: id, code: 'wrong_alias' })]);
  });

  it('deduplicates replays, refuses a conflicting event and never lets a lower sequence win', async () => {
    const first = payload();
    await deliver(first);
    await deliver(first);
    const conflict = await deliver({ ...first, summary: 'Different text under the same event ID' });
    await deliver(payload({ sequence: 9, summary: 'Older news' }));
    const view = await overview();
    expect(view.services[0].reports).toHaveLength(1);
    expect(view.services[0].reports[0]).toMatchObject({ sequence: 17, summary: 'Scheduled health work is overdue' });
    expect(view.verificationIssues).toEqual([expect.objectContaining({ sourceId: conflict, code: 'conflicting_event' })]);
    await deliver(payload({ sequence: 18, summary: 'Health work recovered', facts: { job: 'health', state: 'ok' } }));
    expect((await overview()).services[0].reports[0]).toMatchObject({ sequence: 18, summary: 'Health work recovered' });
  });

  it('refuses reports under a revoked key', async () => {
    expect((await on('POST', `/report-keys/${keyId}/revoke`, {})).body.data.revoked).toBe(true);
    const id = await deliver(payload());
    expect((await overview()).verificationIssues).toEqual([expect.objectContaining({ sourceId: id, code: 'unknown_key' })]);
  });

  it('refuses readiness for a resource outside the owner catalogue', async () => {
    const id = await deliver(payload({ kind: 'readiness', concernKey: 'readiness:domain', facts: { resourceRef: 'domain:other.example', kind: 'domain_expiry', state: 'due', evidenceAt: '2026-10-01T00:00:00Z', dueAt: '2026-11-01T00:00:00Z' } }));
    expect((await overview()).verificationIssues).toEqual([expect.objectContaining({ sourceId: id, code: 'unknown_resource' })]);
  });

  it('keeps alias changes consistent with active keys', async () => {
    const res = await on('PATCH', `/services/${serviceId}`, { expectedRevision: 1, aliases: [] });
    expect(res.body.error?.code).toBe('alias_in_use');
    const other = await on('POST', '/services', { name: 'Other', aliases: [ALIAS], targets: [] });
    expect(other.body.error?.code).toBe('alias_taken');
  });
});

describe('report schema', () => {
  const sid = '86b61b5c-5f3e-4809-900a-8eaa218519ad';
  const base = {
    eventId: 'c93242f1-785e-4782-afda-49c190bcbe31',
    serviceId: sid,
    kind: 'maintenance',
    occurredAt: '2026-10-03T11:00:00Z',
    sequence: 1,
    concernKey: 'maintenance:health',
    classification: 'metadata_only',
    coverage: { scope: 'single_observation', status: 'complete', omittedCount: 0 },
    summary: 'ok',
    facts: {},
    links: [],
  };
  const check = (p: object) => validateReport(encoder.encode(JSON.stringify(p)), T0);

  it('accepts the documented example shape', () => {
    expect(check(base).ok).toBe(true);
  });

  it.each([
    [{ ...base, statement: 'private customer text' }, 'unknown_field'],
    [{ ...base, facts: { exception: 'stack trace' } }, 'unknown_fact'],
    [{ ...base, kind: 'operator_decision', facts: { requestId: 'r1', category: 'free text' } }, 'invalid_fact'],
    [{ ...base, coverage: { scope: 'single_observation', status: 'complete', omittedCount: 3 } }, 'invalid_coverage'],
    [{ ...base, occurredAt: new Date(T0 + FUTURE_SKEW_MS + 60_000).toISOString().replace(/\.\d+Z$/, 'Z') }, 'future_timestamp'],
    [{ ...base, classification: 'full' }, 'invalid_classification'],
    [{ ...base, links: [{ label: 'x', url: 'http://plain.example' }] }, 'invalid_links'],
  ])('rejects %#', (p, code) => {
    expect(check(p)).toEqual({ ok: false, code });
  });

  it('refuses malformed wrappers before reading the payload', () => {
    expect(parseWrapper(encoder.encode('not json'))).toEqual({ ok: false, code: 'malformed_wrapper' });
    expect(parseWrapper(encoder.encode(JSON.stringify({ schemaVersion: 2, keyId: sid, payloadBase64url: '', signature: '' })))).toEqual({ ok: false, code: 'unsupported_version' });
    expect(parseWrapper(encoder.encode(JSON.stringify({ schemaVersion: 1, keyId: sid, payloadBase64url: 'e30', signature: 'short' })))).toEqual({ ok: false, code: 'malformed_wrapper' });
  });
});
