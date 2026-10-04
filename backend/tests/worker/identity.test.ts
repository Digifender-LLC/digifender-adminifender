import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { handleFetch } from '../../src/http/router';
import { ACCESS_MS, REFRESH_MS } from '../../src/auth/sessions';
import { FakeAuthenticator } from './fake-authenticator';

const t0 = Date.UTC(2026, 9, 3, 12);
const ORIGIN = 'http://localhost:8787';

async function call(method: string, path: string, body?: unknown, options: { auth?: string; now?: number } = {}) {
  const headers: Record<string, string> = { 'cf-connecting-ip': '192.0.2.10' };
  if (body !== undefined) headers['content-type'] = 'application/json';
  if (options.auth) headers.authorization = options.auth;
  const request = new Request(`https://api.example.invalid/api/v1${path}`, { method, headers, body: body === undefined ? null : JSON.stringify(body) });
  const response = await handleFetch(request, env, options.now ?? t0);
  return { status: response.status, body: (await response.json()) as { data: any; error?: { code: string } } };
}

async function installBootstrap(token = `afb_${'A'.repeat(43)}`, expiresAt = t0 + 15 * 60 * 1000) {
  const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token)))].map((b) => b.toString(16).padStart(2, '0')).join('');
  await env.CONTROL_DB.prepare("INSERT INTO auth_challenges(id, kind, challenge_digest, context_json, expires_at) VALUES (?, 'bootstrap', ?, '{}', ?)").bind(crypto.randomUUID(), digest, expiresAt).run();
  return token;
}

async function enroll(authenticator = new FakeAuthenticator('localhost', ORIGIN)) {
  const token = await installBootstrap();
  const options = await call('POST', '/auth/bootstrap/options', { bootstrapToken: token, deviceName: 'Galaxy S24+' });
  expect(options.status).toBe(200);
  const credential = await authenticator.register(options.body.data.options);
  const verified = await call('POST', '/auth/bootstrap/verify', { challengeId: options.body.data.challengeId, credential });
  expect(verified.status).toBe(200);
  return { authenticator, token, enrolled: verified.body.data as { deviceId: string; accessToken: string; refreshToken: string; recoverySecret: string } };
}

async function signIn(authenticator: FakeAuthenticator, now = t0) {
  const options = await call('POST', '/auth/passkey/options', { purpose: 'sign_in' }, { now });
  const assertion = await authenticator.assert(options.body.data.options);
  return call('POST', '/auth/passkey/verify', { challengeId: options.body.data.challengeId, assertion, deviceName: 'Tablet' }, { now });
}

describe('owner bootstrap', () => {
  it('enrolls exactly one owner with a passkey, device, session and one-time recovery secret', async () => {
    const { enrolled } = await enroll();
    expect(enrolled.accessToken).toMatch(/^afa_/);
    expect(enrolled.recoverySecret).toMatch(/^afk_/);
    const devices = await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}` });
    expect(devices.body.data.devices).toEqual([expect.objectContaining({ id: enrolled.deviceId, name: 'Galaxy S24+', current: true, revoked: false })]);
    const stored = await env.CONTROL_DB.prepare('SELECT secret_digest FROM recovery_keys').first<{ secret_digest: string }>();
    expect(stored?.secret_digest).not.toContain(enrolled.recoverySecret);
  });

  it('consumes the bootstrap credential and rejects a second owner', async () => {
    const { token } = await enroll();
    const again = await call('POST', '/auth/bootstrap/options', { bootstrapToken: token, deviceName: 'Other' });
    expect(again).toMatchObject({ status: 403, body: { error: { code: 'bootstrap_unavailable' } } });
  });

  it('rejects an expired or unknown bootstrap credential', async () => {
    const expired = await installBootstrap(`afb_${'B'.repeat(43)}`, t0 - 1);
    expect((await call('POST', '/auth/bootstrap/options', { bootstrapToken: expired, deviceName: 'X' })).status).toBe(403);
    expect((await call('POST', '/auth/bootstrap/options', { bootstrapToken: `afb_${'C'.repeat(43)}`, deviceName: 'X' })).status).toBe(403);
  });

  it('rejects a registration from an unexpected origin and keeps the challenge usable', async () => {
    const token = await installBootstrap();
    const options = await call('POST', '/auth/bootstrap/options', { bootstrapToken: token, deviceName: 'Phone' });
    const authenticator = new FakeAuthenticator('localhost', ORIGIN);
    const forged = await authenticator.register(options.body.data.options, { origin: 'https://evil.example' });
    expect((await call('POST', '/auth/bootstrap/verify', { challengeId: options.body.data.challengeId, credential: forged })).body.error?.code).toBe('verification_failed');
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM owner').first()).toEqual({ n: 0 });
  });

  it('rejects unknown fields and oversized bodies', async () => {
    expect((await call('POST', '/auth/bootstrap/options', { bootstrapToken: `afb_${'A'.repeat(43)}`, deviceName: 'X', admin: true })).body.error?.code).toBe('invalid_request');
    const huge = await call('POST', '/auth/bootstrap/options', { bootstrapToken: 'x', deviceName: 'y'.repeat(300 * 1024) });
    expect(huge.status).toBe(413);
  });

  it('rate-limits bootstrap attempts before verification', async () => {
    for (let i = 0; i < 5; i += 1) await call('POST', '/auth/bootstrap/options', { bootstrapToken: `afb_${'D'.repeat(43)}`, deviceName: 'X' });
    const limited = await call('POST', '/auth/bootstrap/options', { bootstrapToken: `afb_${'D'.repeat(43)}`, deviceName: 'X' });
    expect(limited.status).toBe(429);
  });
});

describe('passkey sign-in and sessions', () => {
  it('signs in with the enrolled passkey on a new device', async () => {
    const { authenticator } = await enroll();
    const result = await signIn(authenticator);
    expect(result.status).toBe(200);
    expect(result.body.data.deviceId).toMatch(/^[0-9a-f-]{36}$/);
    const devices = await call('GET', '/devices', undefined, { auth: `Bearer ${result.body.data.accessToken}` });
    expect(devices.body.data.devices).toHaveLength(2);
  });

  it('rejects an unknown credential and a replayed challenge', async () => {
    const { authenticator } = await enroll();
    const stranger = new FakeAuthenticator('localhost', ORIGIN);
    expect((await signIn(stranger)).status).toBe(401);
    const options = await call('POST', '/auth/passkey/options', { purpose: 'sign_in' });
    const assertion = await authenticator.assert(options.body.data.options);
    expect((await call('POST', '/auth/passkey/verify', { challengeId: options.body.data.challengeId, assertion })).status).toBe(200);
    const replay = await call('POST', '/auth/passkey/verify', { challengeId: options.body.data.challengeId, assertion });
    expect(replay.body.error?.code).toBe('challenge_expired');
  });

  it('requires user verification', async () => {
    const { authenticator } = await enroll();
    const options = await call('POST', '/auth/passkey/options', { purpose: 'sign_in' });
    const assertion = await authenticator.assert(options.body.data.options, { userVerified: false });
    expect((await call('POST', '/auth/passkey/verify', { challengeId: options.body.data.challengeId, assertion })).status).toBe(401);
  });

  it('expires access tokens after 15 minutes', async () => {
    const { enrolled } = await enroll();
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}`, now: t0 + ACCESS_MS - 1 })).status).toBe(200);
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}`, now: t0 + ACCESS_MS })).status).toBe(401);
  });

  it('rotates refresh tokens and revokes the family when an old one is replayed', async () => {
    const { enrolled } = await enroll();
    const first = await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}`, now: t0 + 1000 });
    expect(first.status).toBe(200);
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}`, now: t0 + 1000 })).status).toBe(401);
    const replay = await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}`, now: t0 + 2000 });
    expect(replay.status).toBe(401);
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${first.body.data.accessToken}`, now: t0 + 2000 })).status).toBe(401);
    expect((await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${first.body.data.refreshToken}`, now: t0 + 3000 })).status).toBe(401);
  });

  it('keeps the 30-day refresh limit absolute across rotations', async () => {
    const { enrolled } = await enroll();
    const rotated = await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}`, now: t0 + REFRESH_MS - 10_000 });
    expect(rotated.body.data.refreshExpiresAt).toBe(new Date(t0 + REFRESH_MS).toISOString());
    expect((await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${rotated.body.data.refreshToken}`, now: t0 + REFRESH_MS })).status).toBe(401);
  });

  it('upgrades only the initiating session with a recent passkey check', async () => {
    const { authenticator, enrolled } = await enroll();
    const other = await signIn(authenticator, t0 + 1);
    const later = t0 + 10 * 60 * 1000;
    const refreshed = await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}`, now: later });
    const options = await call('POST', '/auth/passkey/options', { purpose: 'recent' }, { auth: `Bearer ${refreshed.body.data.accessToken}`, now: later });
    const assertion = await authenticator.assert(options.body.data.options);
    const verified = await call('POST', '/auth/passkey/verify', { challengeId: options.body.data.challengeId, assertion }, { now: later });
    expect(verified.body.data).toEqual({ recentAuthAt: new Date(later).toISOString() });
    const rows = await env.CONTROL_DB.prepare('SELECT device_id, recent_auth_at FROM sessions WHERE revoked_at IS NULL AND consumed_at IS NULL ORDER BY created_at').all<{ device_id: string; recent_auth_at: number }>();
    expect(rows.results.find((r) => r.device_id === enrolled.deviceId)?.recent_auth_at).toBe(later);
    expect(rows.results.find((r) => r.device_id === other.body.data.deviceId)?.recent_auth_at).toBe(t0 + 1);
  });

  it('logs out the whole session family', async () => {
    const { enrolled } = await enroll();
    expect((await call('POST', '/auth/logout', undefined, { auth: `Bearer ${enrolled.accessToken}` })).status).toBe(200);
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}` })).status).toBe(401);
    expect((await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}` })).status).toBe(401);
  });
});

describe('recovery', () => {
  it('replaces passkeys, revokes every session and rotates the recovery secret', async () => {
    const { authenticator, enrolled } = await enroll();
    const replacement = new FakeAuthenticator('localhost', ORIGIN);
    const options = await call('POST', '/auth/recovery/options', { recoverySecret: enrolled.recoverySecret, newDeviceName: 'Replacement phone' });
    expect(options.status).toBe(200);
    const credential = await replacement.register(options.body.data.options);
    const recovered = await call('POST', '/auth/recovery/verify', { challengeId: options.body.data.challengeId, credential });
    expect(recovered.status).toBe(200);
    expect(recovered.body.data.recoverySecret).not.toBe(enrolled.recoverySecret);

    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${enrolled.accessToken}` })).status).toBe(401);
    expect((await call('POST', '/auth/refresh', undefined, { auth: `Refresh ${enrolled.refreshToken}` })).status).toBe(401);
    expect((await signIn(authenticator)).status).toBe(401);
    expect((await signIn(replacement)).status).toBe(200);
    expect((await call('GET', '/devices', undefined, { auth: `Bearer ${recovered.body.data.accessToken}` })).status).toBe(200);
    expect(await env.CONTROL_DB.prepare('SELECT auth_epoch FROM owner').first()).toEqual({ auth_epoch: 2 });
  });

  it('cannot replay a used recovery secret', async () => {
    const { enrolled } = await enroll();
    const options = await call('POST', '/auth/recovery/options', { recoverySecret: enrolled.recoverySecret, newDeviceName: 'A' });
    await call('POST', '/auth/recovery/verify', { challengeId: options.body.data.challengeId, credential: await new FakeAuthenticator('localhost', ORIGIN).register(options.body.data.options) });
    const replay = await call('POST', '/auth/recovery/options', { recoverySecret: enrolled.recoverySecret, newDeviceName: 'B' });
    expect(replay).toMatchObject({ status: 403, body: { error: { code: 'recovery_unavailable' } } });
  });

  it('lets only one of two concurrent recovery challenges for the same secret succeed', async () => {
    const { enrolled } = await enroll();
    const a = await call('POST', '/auth/recovery/options', { recoverySecret: enrolled.recoverySecret, newDeviceName: 'A' });
    const b = await call('POST', '/auth/recovery/options', { recoverySecret: enrolled.recoverySecret, newDeviceName: 'B' });
    const first = await call('POST', '/auth/recovery/verify', { challengeId: a.body.data.challengeId, credential: await new FakeAuthenticator('localhost', ORIGIN).register(a.body.data.options) });
    const second = await call('POST', '/auth/recovery/verify', { challengeId: b.body.data.challengeId, credential: await new FakeAuthenticator('localhost', ORIGIN).register(b.body.data.options) });
    expect(first.status).toBe(200);
    expect(second.status).toBe(410);
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM passkeys WHERE revoked_at IS NULL').first()).toEqual({ n: 1 });
  });

  it('rate-limits recovery attempts', async () => {
    for (let i = 0; i < 5; i += 1) await call('POST', '/auth/recovery/options', { recoverySecret: `afk_${'Z'.repeat(43)}`, newDeviceName: 'X' });
    expect((await call('POST', '/auth/recovery/options', { recoverySecret: `afk_${'Z'.repeat(43)}`, newDeviceName: 'X' })).status).toBe(429);
  });
});
