import { base64url } from '../auth/tokens';
import { HttpError } from './errors';

/** Continuation cursors expire after 24 hours (lld.md#contract-conventions). */
export const CURSOR_TTL_MS = 24 * 60 * 60 * 1000;

const encoder = new TextEncoder();

async function key(secret: string): Promise<CryptoKey> {
  if (secret.length < 32) throw new HttpError(503, 'unavailable', 'Paging is not configured.', {}, true);
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}

function fromBase64url(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** Server-HMAC-protected cursor bound to a query digest and expiry. The payload is opaque to clients. */
export async function sealCursor(secret: string, queryDigest: string, state: object, now: number): Promise<string> {
  const payload = encoder.encode(JSON.stringify({ q: queryDigest, s: state, e: now + CURSOR_TTL_MS }));
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', await key(secret), payload));
  return `${base64url(payload)}.${base64url(mac)}`;
}

export async function openCursor<T>(secret: string, queryDigest: string, cursor: string, now: number): Promise<T> {
  const invalid = new HttpError(400, 'invalid_request', 'The cursor is invalid.', { reason: 'cursor' });
  const [payloadPart, macPart] = cursor.split('.');
  if (!payloadPart || !macPart || cursor.length > 4096) throw invalid;
  let payload: Uint8Array<ArrayBuffer>;
  let mac: Uint8Array<ArrayBuffer>;
  try {
    payload = fromBase64url(payloadPart);
    mac = fromBase64url(macPart);
  } catch {
    throw invalid;
  }
  if (!(await crypto.subtle.verify('HMAC', await key(secret), mac, payload))) throw invalid;
  const parsed = JSON.parse(new TextDecoder().decode(payload)) as { q: string; s: T; e: number };
  if (parsed.q !== queryDigest) throw invalid;
  if (parsed.e <= now) throw new HttpError(410, 'cursor_expired', 'Start the list again.');
  return parsed.s;
}
