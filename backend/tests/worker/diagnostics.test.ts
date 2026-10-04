import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import worker from '../../src/index';
import { summarize } from '../../src/diagnostics/export';
import { privateHealth } from '../../src/diagnostics/health';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, ingest, quietEnv, runPending, sessionClock } from './support';

const MIN = 60_000;
let token: string;
let deviceId: string;
let tokenAt: (now: number) => Promise<string>;

const mail = (subject: string) => ['From: billing@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, '', 'Secret body words for the private archive.', ''].join('\r\n');
const health = async (now = T0, e?: Env) => (await api('GET', '/health/private', { token: await tokenAt(now), now, ...(e ? { env: e } : {}) })).body.data;
const codes = (h: { warnings: { code: string }[] }) => h.warnings.map((w) => w.code);

beforeEach(async () => {
  const owner = await enrollOwner();
  token = owner.token;
  deviceId = owner.deviceId;
  tokenAt = sessionClock(owner);
});

describe('private health', () => {
  it('labels missing observations as unobserved or unavailable, never as success', async () => {
    const h = await health();
    expect(h.scheduler).toEqual({ status: 'unobserved', lastPassAt: null });
    expect(h.capture).toMatchObject({ status: 'unobserved', lastReceiptAt: null, waiting: 0, oldestWaitingAgeSeconds: null });
    expect(h.sync).toMatchObject({ status: 'unobserved', lastDeviceSyncAt: null });
    expect(h.inference).toMatchObject({ cloudBound: false, allowance: { status: 'unobserved', observedAt: null } });
    expect(h.notifications).toMatchObject({ status: 'unavailable', adapterConfigured: false, deliverableDevices: 0, lastSubmittedAt: null });
    expect(h.devices).toEqual([{ deviceId, readiness: 'unobserved', readinessReportedAt: null, modelLoaded: null, lastSyncAt: null, push: 'unregistered' }]);
    expect(h.backlog).toEqual({ oldestAgeSeconds: null, deadJobs: 0 });
    expect(codes(h)).toEqual(expect.arrayContaining(['scheduler_unobserved', 'push_unavailable']));
    expect(h.storage.partitions.map((p: { role: string }) => p.role)).toEqual(['control', 'archive']);
    expect(typeof h.storage.partitions[0].sizeBytes).toBe('number');
    expect((await api('GET', '/health/private', { now: T0 })).status).toBe(401);
  });

  it('reports capture, parsing and triage backlog separately and marks stale telemetry', async () => {
    const sourceId = await ingest(mail('Invoice'), { now: T0 });
    // Triage has not run: after 15 minutes the triage backlog warns while capture and parsing stay healthy.
    let h = await health(T0 + 16 * MIN);
    expect(h.capture).toMatchObject({ status: 'ok', lastReceiptAt: new Date(T0).toISOString(), waiting: 0 });
    expect(h.parsing).toMatchObject({ status: 'ok', open: 0 });
    expect(h.triage).toMatchObject({ status: 'warning', open: 1, oldestAgeSeconds: 16 * 60 });
    expect(codes(h)).toContain('triage_backlog');
    expect(await runPending('triage', T0 + 16 * MIN)).toEqual(['complete']);

    // Readiness older than the routing window is stale, not ready.
    expect((await api('POST', '/device-work/readiness', { token: await tokenAt(T0 + 16 * MIN), now: T0 + 16 * MIN, key: '00000000-0000-4000-b000-000000000001', body: { foreground: true, charging: true, batteryPercent: 80, thermal: 'none', modelId: 'local-test-model', modelManifestHash: 'a'.repeat(64) } })).status).toBe(200);
    h = await health(T0 + 16 * MIN);
    expect(h.devices[0]).toMatchObject({ readiness: 'ok', modelLoaded: true });
    expect(h.triage.status).toBe('ok');
    h = await health(T0 + 20 * MIN);
    expect(h.devices[0].readiness).toBe('stale');

    // A sync that reaches the end of the feed records the device's catch-up time.
    expect((await api('GET', '/sync/changes?after=0', { token: await tokenAt(T0 + 20 * MIN), now: T0 + 20 * MIN })).status).toBe(200);
    h = await health(T0 + 20 * MIN);
    expect(h.sync).toMatchObject({ status: 'ok', lastDeviceSyncAt: new Date(T0 + 20 * MIN).toISOString() });
    expect(h.sync.latestSeq).toBeGreaterThan(0);

    // Nothing private leaves the view.
    const text = JSON.stringify(h);
    expect(text).not.toMatch(/Invoice|Secret body|vendor\.example|inbox@/);
    expect(text).not.toContain(sourceId);
  });

  it('records a completed scheduled pass and reports it stale after five minutes', async () => {
    const e = quietEnv();
    await worker.scheduled!({ scheduledTime: T0, cron: '* * * * *', noRetry() {} } as ScheduledController, e, {} as ExecutionContext);
    const pass = await env.CONTROL_DB.prepare("SELECT observed_at FROM observations WHERE name = 'scheduled_pass'").first<{ observed_at: number }>();
    expect(pass).not.toBeNull();
    const at = pass!.observed_at;
    // The pass runs on the real clock, so read the projection directly at times relative to it.
    expect((await privateHealth(e, at + MIN)).scheduler).toEqual({ status: 'ok', lastPassAt: new Date(at).toISOString() });
    const late = await privateHealth(e, at + 6 * MIN);
    expect(late.scheduler.status).toBe('stale');
    expect(codes(late)).toContain('scheduler_stale');
  });
});

describe('diagnostics export', () => {
  it('exports versions, counts and opaque failure IDs without content', async () => {
    await ingest(mail('Invoice'), { now: T0 });
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'dead', error_code = 'handler_exception' WHERE kind = 'triage'").run();
    const res = await api('GET', '/diagnostics', { token });
    expect(res.status).toBe(200);
    const d = res.body.data;
    expect(d.release).toMatchObject({ controlMigration: 12 });
    expect(d.migrations.control).toContain('0010_observations.sql');
    expect(d.recentFailures).toEqual([expect.objectContaining({ kind: 'triage', state: 'dead', errorCode: 'handler_exception' })]);
    expect(d.jobs).toEqual(expect.arrayContaining([{ kind: 'parse', state: 'complete', count: 1 }]));
    expect(d.timings.lastDay.chatTurns).toEqual({ count: 0, p50Ms: null, maxMs: null });
    expect(JSON.stringify(d)).not.toMatch(/Invoice|Secret body|vendor\.example|inbox@|raw\//);
    expect((await api('GET', '/health', {})).body.data).toMatchObject({ status: 'live' });
    expect(summarize([30, 10, 20, 40])).toEqual({ count: 4, p50Ms: 20, maxMs: 40 });
  });
});
