import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import type { Env } from '../../src/env';
import { scheduleInactivity } from '../../src/notifications/events';
import type { PushMessage, PushOutcome, PushSender } from '../../src/notifications/push';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const MIN = 60_000;
const HOUR = 60 * MIN;
const PUSH_TOKEN = 'synthetic-fcm-token_0123456789:abcdefghijklmnop';

let token: string;
let deviceId: string;
let refreshToken: string;
let tokenTime = T0;

/** An access token valid at `now`, refreshing the session when the clock has moved on. */
async function tokenAt(now: number): Promise<string> {
  if (now - tokenTime < 5 * 60_000) return token;
  const refreshed = await api('POST', '/auth/refresh', { auth: `Refresh ${refreshToken}`, now });
  if (refreshed.status !== 200) throw new Error(`refresh failed ${refreshed.status}`);
  token = refreshed.body.data.accessToken;
  refreshToken = refreshed.body.data.refreshToken;
  tokenTime = now;
  return token;
}
let keyCount = 0;
const key = () => `00000000-0000-4000-a000-${String((keyCount += 1)).padStart(12, '0')}`;

const mail = (o: { subject: string; id?: string; inReplyTo?: string; body?: string }) =>
  [
    'From: ops@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${o.subject}`,
    ...(o.id ? [`Message-ID: ${o.id}`] : []),
    ...(o.inReplyTo ? [`In-Reply-To: ${o.inReplyTo}`] : []),
    '',
    o.body ?? 'Private body text that must never reach a push payload.',
    '',
  ].join('\r\n');

function fakePush(outcomes: PushOutcome[] = []): { push: PushSender; sends: { token: string; message: PushMessage }[] } {
  const sends: { token: string; message: PushMessage }[] = [];
  return {
    sends,
    push: {
      async send(t, message) {
        sends.push({ token: t, message });
        return outcomes.shift() ?? { outcome: 'sent', receipt: `0:receipt-${sends.length}` };
      },
    },
  };
}

async function triaged(raw: string, now = T0): Promise<string> {
  const sourceId = await ingest(raw, { now });
  expect(await runPending('triage', now)).toEqual(['complete']);
  const row = await env.CONTROL_DB.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(sourceId).first<{ concern_id: string }>();
  return row!.concern_id;
}

const rule = (effects: object[], subject: string) => api('POST', '/rules', { token, key: key(), body: { predicate: { subject_contains: subject }, effects } });
const register = async (permission = 'granted', pushToken = PUSH_TOKEN, now = T0) =>
  api('POST', `/devices/${deviceId}/push`, { token: await tokenAt(now), now, key: key(), body: { token: pushToken, permission, appVersion: '0.1.0' } });
const act = async (id: string, expectedRevision: number, kind: string, args?: object, now = T0) =>
  api('POST', `/concerns/${id}/actions`, { token: await tokenAt(now), now, key: key(), body: { expectedRevision, kind, ...(args ? { args } : {}) } });
const revision = async (id: string, now = T0) => (await api('GET', `/concerns/${id}`, { token: await tokenAt(now), now })).body.data.concern.revision as number;
const events = async (now = T0) => (await api('GET', '/notifications', { token: await tokenAt(now), now })).body.data.events as any[];
const notify = (now: number, push?: PushSender) => runPending('notify', now, push ? ({ PUSH: push } as Partial<Env>) : {});
const setNotifications = async (patch: object, now = T0) => {
  const current = (await api('GET', '/settings', { token, now })).body.data;
  const res = await api('PATCH', '/settings', { token, now, key: key(), body: { expectedRevision: current.revision, notifications: { ...current.notifications, ...patch } } });
  expect(res.status).toBe(200);
  return res.body.data;
};

beforeEach(async () => {
  const owner = await enrollOwner();
  token = owner.token;
  deviceId = owner.deviceId;
  refreshToken = owner.refreshToken;
  tokenTime = T0;
});

describe('critical notifications', () => {
  it('pushes one opaque critical event per material signal, bypassing quiet hours', async () => {
    await rule([{ critical: true }], 'outage');
    expect((await register()).status).toBe(200);
    const concernId = await triaged(mail({ subject: 'Production outage', id: '<a@vendor.example>' }));

    // Owner edits never mint another critical signal.
    expect((await act(concernId, 1, 'mark_read')).status).toBe(200);
    // Inside quiet hours (UTC 22:00–08:00): critical still goes out.
    const night = Date.UTC(2026, 9, 3, 23);
    const { push, sends } = fakePush();
    expect(await notify(night, push)).toEqual(['complete']);
    expect(sends).toHaveLength(1);
    expect(sends[0]!.token).toBe(PUSH_TOKEN);
    const { eventId, ...rest } = sends[0]!.message;
    expect(rest).toEqual({ kind: 'critical', destinationId: concernId, critical: true, ttlSeconds: (24 * HOUR - 11 * HOUR) / 1000 });
    expect(JSON.stringify(sends[0]!.message)).not.toMatch(/outage|Private body|vendor/i);

    const [event] = await events(night);
    expect(event).toMatchObject({ id: eventId, kind: 'critical', concernId, state: 'sent', reason: null });
    expect(event.deliveries).toEqual([{ deviceId, state: 'sent', errorCode: null, submittedAt: new Date(night).toISOString() }]);

    // An acknowledge or snooze edit adds nothing; a new material critical source in the thread does.
    expect((await act(concernId, 2, 'acknowledge', undefined, night)).status).toBe(200);
    expect((await act(concernId, 3, 'snooze', { until: new Date(night + HOUR).toISOString() }, night)).status).toBe(200);
    expect(await events(night)).toHaveLength(1);
    await triaged(mail({ subject: 'Re: Production outage', inReplyTo: '<a@vendor.example>', body: 'Escalated.' }), night + MIN);
    expect((await events(night + MIN)).map((e) => e.kind)).toEqual(['critical', 'critical']);
    expect(await notify(night + MIN, push)).toEqual(['complete']);
    expect(sends).toHaveLength(2);
  });

  it('does not push an owner correction or AI-only importance as critical', async () => {
    const concernId = await triaged(mail({ subject: 'Invoice' }));
    const corrected = await api('POST', `/concerns/${concernId}/corrections`, { token, key: key(), body: { expectedRevision: 1, importance: 'critical', applyTo: 'this_concern' } });
    expect(corrected.status).toBe(200);
    expect(await events()).toEqual([]);
  });

  it('suppresses a critical event acknowledged before dispatch', async () => {
    await rule([{ critical: true }], 'outage');
    await register();
    const concernId = await triaged(mail({ subject: 'Outage' }));
    expect((await act(concernId, 1, 'acknowledge')).status).toBe(200);
    const { push, sends } = fakePush();
    expect(await notify(T0, push)).toEqual(['complete']);
    expect(sends).toHaveLength(0);
    expect((await events())[0]).toMatchObject({ state: 'suppressed', reason: 'acknowledged' });
  });
});

describe('delivery failures', () => {
  beforeEach(async () => {
    await rule([{ critical: true }], 'outage');
  });

  it('reports denied permission and no adapter without changing the inbox', async () => {
    await register('denied');
    const concernId = await triaged(mail({ subject: 'Outage' }));
    const { push, sends } = fakePush();
    expect(await notify(T0, push)).toEqual(['complete']);
    expect(sends).toHaveLength(0);
    expect((await events())[0]).toMatchObject({ state: 'failed', reason: 'no_push_device' });
    expect((await api('GET', '/devices', { token })).body.data.devices[0]).toMatchObject({ push: 'denied', pushRegistered: true });
    expect((await api('GET', `/concerns/${concernId}`, { token })).body.data.concern).toMatchObject({ status: 'open', revision: 1 });

    await register('granted');
    await triaged(mail({ subject: 'Outage again' }), T0 + MIN);
    expect(await notify(T0 + MIN)).toEqual(['complete']);
    expect((await events(T0 + MIN))[0]).toMatchObject({ state: 'failed', reason: 'push_unavailable' });
  });

  it('sends nothing to a device that signed out', async () => {
    await register();
    expect((await api('POST', '/auth/logout', { token })).status).toBe(200);
    await triaged(mail({ subject: 'Outage' }));
    const { push, sends } = fakePush();
    expect(await notify(T0, push)).toEqual(['complete']);
    expect(sends).toHaveLength(0);
    // The session is gone, so read the outcome from the database rather than the API.
    const row = await env.CONTROL_DB.prepare("SELECT state, reason FROM notification_events WHERE kind = 'critical'").first();
    expect(row).toEqual({ state: 'failed', reason: 'no_push_device' });
  });

  it('disables an invalid token until the phone registers a rotated one', async () => {
    await register();
    await triaged(mail({ subject: 'Outage' }));
    const { push } = fakePush([{ outcome: 'invalid_token' }]);
    expect(await notify(T0, push)).toEqual(['complete']);
    expect((await events())[0]).toMatchObject({ state: 'failed', reason: 'invalid_token', deliveries: [{ state: 'failed', errorCode: 'invalid_token' }] });
    expect((await api('GET', '/devices', { token })).body.data.devices[0].push).toBe('invalid');

    await triaged(mail({ subject: 'Outage two' }), T0 + MIN);
    expect(await notify(T0 + MIN, push)).toEqual(['complete']);
    expect((await events(T0 + MIN))[0]).toMatchObject({ state: 'failed', reason: 'no_push_device' });

    expect((await register('granted', 'rotated-synthetic-token_9876543210')).status).toBe(200);
    expect((await api('GET', '/devices', { token })).body.data.devices[0].push).toBe('granted');
  });

  it('keeps a lost send response uncertain and never sends the event again', async () => {
    await register();
    await triaged(mail({ subject: 'Outage' }));
    const { push, sends } = fakePush([{ outcome: 'uncertain' }]);
    expect(await notify(T0, push)).toEqual(['complete']);
    expect((await events())[0]).toMatchObject({ state: 'uncertain', reason: 'send_uncertain', deliveries: [{ state: 'uncertain' }] });
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'pending' WHERE kind = 'notify'").run();
    expect(await notify(T0 + MIN, push)).toEqual(['complete']);
    expect(sends).toHaveLength(1);
  });

  it('turns an interrupted send into an uncertain delivery instead of resending', async () => {
    await register();
    await triaged(mail({ subject: 'Outage' }));
    const event = (await events())[0];
    // A previous run recorded dispatch and then lost its lease before recording the provider answer.
    await env.CONTROL_DB.prepare("INSERT INTO notification_deliveries(event_id, device_id, state, dispatched_at) VALUES (?, ?, 'dispatching', ?)").bind(event.id, deviceId, T0).run();
    const { push, sends } = fakePush();
    expect(await notify(T0 + MIN, push)).toEqual(['complete']);
    expect(sends).toHaveLength(0);
    expect((await events(T0 + MIN))[0]).toMatchObject({ state: 'uncertain', deliveries: [{ state: 'uncertain', errorCode: 'interrupted' }] });
  });

  it('suppresses a disabled channel and an expired event', async () => {
    await register();
    await triaged(mail({ subject: 'Outage' }));
    const { push, sends } = fakePush();
    expect(await notify(T0 + 25 * HOUR, push)).toEqual(['complete']);
    expect((await events())[0]).toMatchObject({ state: 'suppressed', reason: 'expired' });
    await setNotifications({ channels: { critical: false, due: true, inactivity: true } });
    await triaged(mail({ subject: 'Outage two' }), T0 + MIN);
    expect(await notify(T0 + MIN, push)).toEqual(['complete']);
    expect((await events(T0 + MIN))[0]).toMatchObject({ state: 'suppressed', reason: 'channel_disabled' });
    expect(sends).toHaveLength(0);
  });
});

describe('due reminders', () => {
  it('reminds once per due revision, waits out quiet hours and drops superseded dates', async () => {
    await register();
    const concernId = await triaged(mail({ subject: 'Renewal' }));
    const due = Date.UTC(2026, 9, 4, 23); // inside 22:00–08:00 UTC quiet hours
    expect((await act(concernId, 1, 'set_due', { at: new Date(due).toISOString() })).status).toBe(200);
    expect((await act(concernId, 2, 'mark_read')).status).toBe(200);
    const listed = await events();
    expect(listed).toHaveLength(1);
    expect(listed[0]).toMatchObject({ kind: 'due', notBefore: new Date(due).toISOString(), expiresAt: new Date(due + 24 * HOUR).toISOString() });

    const { push, sends } = fakePush();
    expect(await notify(due - MIN, push)).toEqual([]);
    expect(await notify(due, push)).toEqual(['deferred']);
    const morning = Date.UTC(2026, 9, 5, 8);
    expect(await notify(morning - MIN, push)).toEqual([]);
    expect(await notify(morning, push)).toEqual(['complete']);
    expect(sends.map((s) => s.message.kind)).toEqual(['due']);
    expect(sends[0]!.message.critical).toBe(false);

    // A new date is a new revision; its predecessor, if still pending, is superseded.
    const later = morning + 2 * HOUR;
    expect((await act(concernId, await revision(concernId, morning), 'set_due', { at: new Date(later).toISOString() }, morning)).status).toBe(200);
    expect((await act(concernId, await revision(concernId, morning), 'set_due', { at: new Date(later + HOUR).toISOString() }, morning)).status).toBe(200);
    expect(await notify(later + HOUR, push)).toEqual(['complete', 'complete']);
    const states = (await events(later + HOUR)).map((e) => [e.state, e.reason]);
    expect(states).toEqual([
      ['sent', null],
      ['suppressed', 'superseded'],
      ['sent', null],
    ]);
  });
});

describe('inactivity reminders', () => {
  const surface = () => rule([{ surface: true }], 'Renewal');

  it('reminds after inactivity with eligible backlog, once per cooldown', async () => {
    await surface();
    await triaged(mail({ subject: 'Renewal' }));
    // App launch and sync are not meaningful review.
    expect((await api('GET', '/sync/bootstrap', { token: await tokenAt(T0 + 23 * HOUR), now: T0 + 23 * HOUR })).status).toBe(200);
    expect(await scheduleInactivity(env.CONTROL_DB, T0 + 23 * HOUR)).toEqual({ created: false, reason: 'recently_reviewed' });
    const due = T0 + 25 * HOUR; // 13:00 UTC the next day
    expect(await scheduleInactivity(env.CONTROL_DB, due)).toEqual({ created: true, reason: 'created' });
    expect(await scheduleInactivity(env.CONTROL_DB, due + HOUR)).toEqual({ created: false, reason: 'cooldown' });
    await register();
    const { push, sends } = fakePush();
    expect(await notify(due, push)).toEqual(['complete']);
    expect(sends[0]!.message).toMatchObject({ kind: 'inactivity', destinationId: 'briefing', critical: false });
  });

  it('does not remind for an empty, routine-only or snoozed-only backlog, or during quiet hours', async () => {
    expect(await scheduleInactivity(env.CONTROL_DB, T0 + 25 * HOUR)).toEqual({ created: false, reason: 'no_eligible_backlog' });
    const routine = await triaged(mail({ subject: 'Newsletter' }));
    expect(await scheduleInactivity(env.CONTROL_DB, T0 + 25 * HOUR)).toEqual({ created: false, reason: 'no_eligible_backlog' });
    await surface();
    const important = await triaged(mail({ subject: 'Renewal' }));
    // Snoozing is a meaningful review; the inactivity clock restarts from it.
    expect((await act(important, 1, 'snooze', { until: new Date(T0 + 72 * HOUR).toISOString() })).status).toBe(200);
    expect(await scheduleInactivity(env.CONTROL_DB, T0 + 25 * HOUR)).toEqual({ created: false, reason: 'no_eligible_backlog' });
    expect(routine).not.toBe(important);
    expect(await scheduleInactivity(env.CONTROL_DB, Date.UTC(2026, 9, 4, 23))).toEqual({ created: false, reason: 'quiet_hours' });
  });

  it('drops a pending reminder after a meaningful review', async () => {
    await surface();
    const concernId = await triaged(mail({ subject: 'Renewal' }));
    const due = T0 + 25 * HOUR;
    expect((await scheduleInactivity(env.CONTROL_DB, due)).created).toBe(true);
    expect((await act(concernId, 1, 'acknowledge', undefined, due + MIN)).status).toBe(200);
    await register();
    const { push, sends } = fakePush();
    expect(await notify(due + 2 * MIN, push)).toEqual(['complete']);
    expect(sends).toHaveLength(0);
    expect((await events(due + 2 * MIN)).find((e) => e.kind === 'inactivity')).toMatchObject({ state: 'suppressed', reason: 'reviewed' });
  });
});

describe('push registration and settings', () => {
  it('accepts registration only through the device session and stores the token encrypted', async () => {
    const other = await api('POST', `/devices/${crypto.randomUUID()}/push`, { token, key: key(), body: { token: PUSH_TOKEN, permission: 'granted', appVersion: '0.1.0' } });
    expect(other.status).toBe(403);
    expect(other.body.error?.code).toBe('wrong_device');
    expect((await register()).body.data).toEqual({ deviceId, push: 'granted' });
    const row = await env.CONTROL_DB.prepare('SELECT fcm_token_cipher FROM devices WHERE id = ?').bind(deviceId).first<{ fcm_token_cipher: ArrayBuffer }>();
    expect(new TextDecoder().decode(new Uint8Array(row!.fcm_token_cipher))).not.toContain('synthetic-fcm-token');
  });

  it('validates the notification section and leaves the inference policy revision alone', async () => {
    const current = (await api('GET', '/settings', { token })).body.data;
    expect(current).toMatchObject({ revision: 0, policyRevision: 0, notifications: { zone: 'UTC', quietHours: { start: '22:00', end: '08:00' }, inactivityHours: 24, cooldownHours: 24 } });
    const patch = (notifications: object) => api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: 0, notifications: { ...current.notifications, ...notifications } } });
    expect((await patch({ zone: 'Mars/Olympus' })).body.error).toMatchObject({ code: 'invalid_request', details: { reason: 'unknown_zone' } });
    expect((await patch({ zone: '+02:00' })).status).toBe(400);
    expect((await patch({ quietHours: { start: '20:00', end: '12:30' } })).body.error?.details).toMatchObject({ reason: 'quiet_interval' });
    expect((await patch({ cooldownHours: 0 })).status).toBe(400);
    const saved = await setNotifications({ zone: 'Europe/Berlin', quietHours: null });
    expect(saved).toMatchObject({ revision: 1, policyRevision: 0, notifications: { zone: 'Europe/Berlin', quietHours: null } });
  });
});
