import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { evaluateHeartbeats, evaluateReadiness } from '../../src/reports/obligations';
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

async function deliver(body: object, to = ALIAS, wrapperSecret = secret, wrapperKey = keyId, now = T0): Promise<string> {
  const id = await ingest(reportMail(await signed(wrapperSecret, wrapperKey, body)), { to, env: ON, now });
  await runPending('report', now, ON);
  return id;
}

const HOUR = 3_600_000;
const concernsFor = async (sid: string) =>
  (await env.CONTROL_DB.prepare('SELECT id, title, importance, status, due_at FROM concerns WHERE service_id = ? ORDER BY updated_at, id').bind(sid).all<{ id: string; title: string; importance: string; status: string; due_at: number | null }>()).results;

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

describe('report obligations and records (P2, enabled)', () => {
  beforeEach(async () => {
    serviceId = (await on('POST', '/services', { name: 'Hosting', aliases: [ALIAS], targets: ['domain:example.com'] })).body.data.id;
    const created = await on('POST', `/services/${serviceId}/report-keys`, { alias: ALIAS });
    keyId = created.body.data.keyId;
    secret = created.body.data.secretBase64url;
  });

  const heartbeat = (sequence: number) => payload({ kind: 'heartbeat', concernKey: 'heartbeat', sequence, summary: 'Alive', facts: { state: 'ok', expectedIntervalSeconds: 3600 } });

  it('raises one evolving concern for a missing heartbeat and lets only a newer one resume it', async () => {
    expect((await on('POST', '/heartbeat-expectations', { serviceId, intervalSeconds: 3600, graceSeconds: 0 })).status).toBe(201);
    await deliver(heartbeat(5));
    expect(await evaluateHeartbeats({ ...env, ...ON }, T0 + HOUR / 2)).toBe(0);
    expect(await evaluateHeartbeats({ ...env, ...ON }, T0 + 2 * HOUR)).toBe(1);
    expect(await evaluateHeartbeats({ ...env, ...ON }, T0 + 3 * HOUR)).toBe(0);
    let concerns = await concernsFor(serviceId);
    expect(concerns).toEqual([expect.objectContaining({ importance: 'important', status: 'open' })]);
    expect(concerns[0]!.title).toContain('No heartbeat received from Hosting since');

    // A replayed older heartbeat does not count as evidence that it resumed.
    await deliver(heartbeat(4), ALIAS, secret, keyId, T0 + 3 * HOUR);
    expect((await concernsFor(serviceId))[0]!.title).toContain('No heartbeat received');
    await deliver(heartbeat(6), ALIAS, secret, keyId, T0 + 3 * HOUR);
    concerns = await concernsFor(serviceId);
    expect(concerns).toHaveLength(1);
    expect(concerns[0]).toMatchObject({ importance: 'routine', status: 'open' });
    expect(concerns[0]!.title).toContain('Heartbeat resumed from Hosting');
    expect((await on('GET', '/operations/overview')).body.data.services[0]).toMatchObject({ expectedEverySeconds: 3600 });
  });

  it('does nothing about heartbeats while reports are disabled', async () => {
    await on('POST', '/heartbeat-expectations', { serviceId, intervalSeconds: 3600, graceSeconds: 0 });
    expect(await evaluateHeartbeats(env, T0 + 48 * HOUR)).toBe(0);
  });

  it('reminds about dated readiness items once and never invents a date', async () => {
    const facts = { resourceRef: 'domain:example.com', kind: 'domain_expiry', state: 'due', evidenceAt: '2026-10-01T00:00:00Z' };
    await deliver(payload({ kind: 'readiness', concernKey: 'readiness:undated', sequence: 1, summary: 'Expiry unknown', facts: { ...facts, dueAt: null } }));
    await deliver(payload({ kind: 'readiness', concernKey: 'readiness:domain', sequence: 2, summary: 'Domain renewal due', facts: { ...facts, dueAt: '2026-10-20T00:00:00Z' } }));
    expect(await evaluateReadiness({ ...env, ...ON }, T0)).toBe(1);
    expect(await evaluateReadiness({ ...env, ...ON }, T0 + HOUR)).toBe(0);
    const concerns = await concernsFor(serviceId);
    expect(concerns).toEqual([expect.objectContaining({ due_at: Date.parse('2026-10-20T00:00:00Z'), importance: 'important' })]);
    expect(concerns[0]!.title).toContain('Domain renewal for domain:example.com');
  });

  it('keeps costs as proposals until the owner confirms, and an amount edit withdraws the confirmation', async () => {
    const sourceId = await deliver(payload());
    const created = await on('POST', '/cost-entries', { sourceId, serviceId, currency: 'USD', amountMicros: 12_500_000, evidenceKind: 'invoice' });
    expect(created.body.data).toMatchObject({ revision: 1, ownerConfirmed: false });
    const id = created.body.data.id;
    expect((await on('PATCH', `/cost-entries/${id}`, { expectedRevision: 1, ownerConfirmed: true })).body.data.ownerConfirmed).toBe(true);
    expect((await on('PATCH', `/cost-entries/${id}`, { expectedRevision: 2, amountMicros: 13_000_000 })).body.data.ownerConfirmed).toBe(false);
    expect((await on('PATCH', `/cost-entries/${id}`, { expectedRevision: 2, ownerConfirmed: true })).body.error?.code).toBe('stale_revision');
  });

  it('returns a reviewed runbook to draft when its content changes', async () => {
    const created = await on('POST', '/runbooks', { title: 'Restart health job', serviceId, steps: [{ text: 'Check the queue depth' }, { text: 'Activate the previous version', actionKind: 'cloudflare.worker.version.activate' }], evidenceRefs: [] });
    const id = created.body.data.id;
    expect((await on('PATCH', `/runbooks/${id}`, { expectedRevision: 1, ownerReviewed: true })).body.data.ownerReviewed).toBe(true);
    expect((await on('PATCH', `/runbooks/${id}`, { expectedRevision: 2, steps: [{ text: 'Changed step' }] })).body.data.ownerReviewed).toBe(false);
  });

  it('shows releases of related services that preceded an incident, without claiming cause', async () => {
    const cdnAlias = 'reports-cdn@ops.example';
    const cdn = (await on('POST', '/services', { name: 'CDN', aliases: [cdnAlias], targets: [] })).body.data.id;
    const cdnKey = (await on('POST', `/services/${cdn}/report-keys`, { alias: cdnAlias })).body.data;
    expect((await on('POST', `/services/${serviceId}/dependencies`, { expectedRevision: 1, childId: cdn, relation: 'depends_on' })).status).toBe(200);
    expect((await on('POST', `/services/${cdn}/dependencies`, { expectedRevision: 1, childId: serviceId, relation: 'depends_on' })).status).toBe(200);
    await deliver(
      { ...payload({ kind: 'release', concernKey: 'release', sequence: 3, summary: 'CDN release', occurredAt: '2026-10-03T10:00:00Z', facts: { releaseId: 'r-1', deploymentState: 'deployed', acceptanceState: 'unverified' } }), serviceId: cdn },
      cdnAlias,
      cdnKey.secretBase64url,
      cdnKey.keyId,
    );
    await deliver(payload({ occurredAt: '2026-10-03T11:00:00Z' }));
    const t = (await on('GET', `/operations/timeline?serviceId=${serviceId}`)).body.data;
    expect(t.relatedServiceIds.sort()).toEqual([serviceId, cdn].sort());
    expect(t.incidents).toHaveLength(1);
    expect(t.incidents[0].precededBy).toEqual([expect.objectContaining({ serviceId: cdn, kind: 'release', summary: 'CDN release' })]);
    expect(t.note).toBe('preceded_is_time_only');
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
