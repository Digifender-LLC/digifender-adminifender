import { describe, expect, it } from 'vitest';
import { assertion, fcmMessage, fcmOutcome, fcmSender, resetFcmCache } from '../../src/notifications/fcm';
import { openToken, sealToken } from '../../src/notifications/push';
import { inQuietHours, isZone, localTime, quietEnd } from '../../src/notifications/zone';

const QUIET = { start: '22:00', end: '08:00' };

describe('quiet hours', () => {
  it('validates IANA zones', () => {
    expect(isZone('Europe/Berlin')).toBe(true);
    expect(isZone('America/Argentina/Buenos_Aires')).toBe(true);
    expect(isZone('UTC')).toBe(true);
    expect(isZone('Mars/Olympus')).toBe(false);
    expect(isZone('+02:00')).toBe(false);
  });

  it('wraps past midnight in local time', () => {
    expect(inQuietHours(Date.UTC(2026, 6, 1, 20, 30), 'Europe/Berlin', QUIET)).toBe(true); // 22:30 CEST
    expect(inQuietHours(Date.UTC(2026, 6, 1, 5, 59), 'Europe/Berlin', QUIET)).toBe(true); // 07:59 CEST
    expect(inQuietHours(Date.UTC(2026, 6, 1, 6), 'Europe/Berlin', QUIET)).toBe(false); // 08:00 CEST
    expect(inQuietHours(Date.UTC(2026, 6, 1, 5, 59), 'UTC', QUIET)).toBe(true);
    expect(inQuietHours(Date.UTC(2026, 6, 1, 12), 'UTC', null)).toBe(false);
    expect(localTime(Date.UTC(2026, 6, 1, 22, 30), 'Europe/Berlin')).toEqual({ date: '2026-07-02', minutes: 30 });
  });

  it('finds the end of quiet hours across DST changes', () => {
    // Autumn: New York falls back on 2026-11-01, so 08:00 local is 13:00 UTC instead of 12:00 UTC.
    expect(quietEnd(Date.UTC(2026, 10, 1, 5), 'America/New_York', QUIET)).toBe(Date.UTC(2026, 10, 1, 13));
    expect(quietEnd(Date.UTC(2026, 9, 31, 5), 'America/New_York', QUIET)).toBe(Date.UTC(2026, 9, 31, 12));
    // Spring: 02:30 does not exist on 2026-03-08; quiet hours end at the first real minute after it.
    expect(quietEnd(Date.UTC(2026, 2, 8, 6), 'America/New_York', { start: '22:00', end: '02:30' })).toBe(Date.UTC(2026, 2, 8, 7));
    expect(quietEnd(Date.UTC(2026, 6, 1, 12, 0, 30), 'UTC', QUIET)).toBe(Date.UTC(2026, 6, 1, 12, 0, 30));
    expect(quietEnd(Date.UTC(2026, 6, 1, 23, 17, 42), 'UTC', QUIET)).toBe(Date.UTC(2026, 6, 2, 8));
  });
});

describe('push token storage', () => {
  it('round-trips only for the same device and key', async () => {
    const sealed = await sealToken('k'.repeat(40), 'device-a', 'token-value');
    expect(await openToken('k'.repeat(40), 'device-a', sealed)).toBe('token-value');
    expect(await openToken('k'.repeat(40), 'device-b', sealed)).toBeNull();
    expect(await openToken('j'.repeat(40), 'device-a', sealed)).toBeNull();
  });
});

describe('FCM adapter', () => {
  const message = { eventId: 'e1', kind: 'critical' as const, destinationId: 'c1', critical: true, ttlSeconds: 3600 };

  it('sends identity and generic text only', () => {
    expect(fcmMessage('tok', message)).toEqual({
      message: {
        token: 'tok',
        data: { eventId: 'e1', kind: 'critical', destinationId: 'c1' },
        notification: { title: 'Adminifender', body: 'Adminifender has an item to review' },
        android: { priority: 'high', ttl: '3600s', collapse_key: 'e1', notification: { tag: 'e1' } },
      },
    });
  });

  it('maps provider answers without keeping provider text', async () => {
    const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
    expect(await fcmOutcome(json(200, { name: 'projects/p/messages/0:123' }))).toEqual({ outcome: 'sent', receipt: '0:123' });
    expect(await fcmOutcome(json(404, { error: { status: 'NOT_FOUND', message: 'secret detail' } }))).toEqual({ outcome: 'invalid_token' });
    expect(await fcmOutcome(json(400, { error: { status: 'INVALID_ARGUMENT', details: [{ errorCode: 'UNREGISTERED' }] } }))).toEqual({ outcome: 'invalid_token' });
    expect(await fcmOutcome(json(400, { error: { status: 'INVALID_ARGUMENT' } }))).toEqual({ outcome: 'rejected', code: 'provider_invalid_argument' });
    expect(await fcmOutcome(json(403, {}))).toEqual({ outcome: 'rejected', code: 'provider_auth' });
    expect(await fcmOutcome(json(503, {}))).toEqual({ outcome: 'rejected', code: 'provider_unavailable' });
  });

  it('signs a service-account assertion and treats a lost send as uncertain', async () => {
    resetFcmCache();
    const pair = (await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify'])) as CryptoKeyPair;
    const pkcs8 = new Uint8Array((await crypto.subtle.exportKey('pkcs8', pair.privateKey)) as ArrayBuffer);
    const pem = `-----BEGIN PRIVATE KEY-----\n${btoa(String.fromCharCode(...pkcs8))}\n-----END PRIVATE KEY-----\n`;
    const account = { project_id: 'synthetic-project', client_email: 'push@synthetic-project.iam.example', private_key: pem };
    const jwt = await assertion(account, Date.UTC(2026, 9, 4));
    const [h, p, s] = jwt.split('.');
    const decode = (part: string) => Uint8Array.from(atob(part.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
    expect(await crypto.subtle.verify('RSASSA-PKCS1-v1_5', pair.publicKey, decode(s!), new TextEncoder().encode(`${h}.${p}`))).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(decode(p!)))).toMatchObject({ iss: account.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging' });

    const calls: string[] = [];
    let lose = false;
    const fetcher = async (url: string, init: RequestInit) => {
      calls.push(url);
      if (url.includes('oauth2')) return new Response(JSON.stringify({ access_token: 'synthetic-access', expires_in: 3600 }));
      if (lose) throw new Error('connection reset');
      expect(JSON.parse(init.body as string).message.token).toBe('device-token');
      return new Response(JSON.stringify({ name: 'projects/synthetic-project/messages/0:abc' }));
    };
    const sender = fcmSender(JSON.stringify(account), fetcher, () => Date.UTC(2026, 9, 4))!;
    expect(await sender.send('device-token', message)).toEqual({ outcome: 'sent', receipt: '0:abc' });
    lose = true;
    expect(await sender.send('device-token', message)).toEqual({ outcome: 'uncertain' });
    expect(calls.filter((c) => c.includes('oauth2'))).toHaveLength(1);
    expect(calls.at(-1)).toBe('https://fcm.googleapis.com/v1/projects/synthetic-project/messages:send');
    expect(fcmSender('{"project_id":"x"}')).toBeNull();
  });
});
