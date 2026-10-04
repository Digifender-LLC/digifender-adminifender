import type { Env } from '../env';
import { fcmSender } from './fcm';

export type EventKind = 'critical' | 'due' | 'inactivity';

/** The whole push payload: opaque identity only. The app fetches current state when it opens. */
export interface PushMessage {
  eventId: string;
  kind: EventKind;
  destinationId: string;
  /** Critical events use high priority; the rest use normal priority. */
  critical: boolean;
  ttlSeconds: number;
}

/**
 * A provider's answer to one send. `uncertain` means no authoritative answer (lost response or timeout): the
 * message may have been accepted, so the event is not sent again. `rejected` is an authoritative refusal.
 */
export type PushOutcome = { outcome: 'sent'; receipt: string } | { outcome: 'invalid_token' } | { outcome: 'rejected'; code: string } | { outcome: 'uncertain' };

export interface PushSender {
  send(token: string, message: PushMessage): Promise<PushOutcome>;
}

/** The configured push adapter, or null when none is configured (delivery is then reported unavailable). */
export function pushSender(env: Env): PushSender | null {
  if (env.PUSH) return env.PUSH;
  return env.FCM_SERVICE_ACCOUNT ? fcmSender(env.FCM_SERVICE_ACCOUNT) : null;
}

const keys = new Map<string, Promise<CryptoKey>>();

function tokenKey(secret: string): Promise<CryptoKey> {
  let key = keys.get(secret);
  if (!key) {
    key = (async () => {
      const material = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(`adminifender:push-token:v1:${secret}`));
      return crypto.subtle.importKey('raw', material, 'AES-GCM', false, ['encrypt', 'decrypt']);
    })();
    keys.set(secret, key);
  }
  return key;
}

/** Push tokens are stored encrypted (AES-GCM, random IV, device ID as associated data). */
export async function sealToken(secret: string, deviceId: string, token: string): Promise<Uint8Array> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const cipher = new Uint8Array(
    await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: new TextEncoder().encode(deviceId) }, await tokenKey(secret), new TextEncoder().encode(token)),
  );
  const out = new Uint8Array(iv.length + cipher.length);
  out.set(iv);
  out.set(cipher, iv.length);
  return out;
}

/** Null when the stored value cannot be opened (for example after a key rotation); the device then re-registers. */
export async function openToken(secret: string, deviceId: string, sealed: ArrayBuffer | Uint8Array | number[]): Promise<string | null> {
  // D1 returns BLOB columns as arrays of byte values.
  const bytes = sealed instanceof Uint8Array ? sealed : new Uint8Array(sealed);
  try {
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: new TextEncoder().encode(deviceId) }, await tokenKey(secret), bytes.slice(12));
    return new TextDecoder().decode(plain);
  } catch {
    return null;
  }
}
