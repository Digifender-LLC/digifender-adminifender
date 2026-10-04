import type { PushMessage, PushOutcome, PushSender } from './push';

/**
 * Firebase Cloud Messaging HTTP v1 adapter. The service-account key stays in a Worker secret and is used only
 * to sign the OAuth assertion here; it is never logged or returned. Not exercised against the live provider
 * until staging (tdd.md#environments-and-preflight).
 */

const TOKEN_URL = 'https://oauth2.googleapis.com/token';
const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
export const SEND_TIMEOUT_MS = 10_000;

interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
}

type Fetch = (input: string, init: RequestInit) => Promise<Response>;

function parseAccount(json: string): ServiceAccount | null {
  try {
    const value = JSON.parse(json) as Partial<ServiceAccount>;
    if (typeof value.project_id !== 'string' || typeof value.client_email !== 'string' || typeof value.private_key !== 'string') return null;
    if (!/^[a-z0-9-]{4,40}$/.test(value.project_id)) return null;
    return { project_id: value.project_id, client_email: value.client_email, private_key: value.private_key };
  } catch {
    return null;
  }
}

const base64url = (bytes: Uint8Array | string): string => {
  const raw = typeof bytes === 'string' ? new TextEncoder().encode(bytes) : bytes;
  let text = '';
  for (const b of raw) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
};

async function signingKey(pem: string): Promise<CryptoKey> {
  const body = pem.replace(/-----(BEGIN|END) PRIVATE KEY-----/g, '').replace(/\s+/g, '');
  const der = Uint8Array.from(atob(body), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}

export async function assertion(account: ServiceAccount, now: number): Promise<string> {
  const iat = Math.floor(now / 1000);
  const unsigned = `${base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${base64url(JSON.stringify({ iss: account.client_email, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 }))}`;
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', await signingKey(account.private_key), new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

async function withTimeout<T>(work: Promise<T>, ms: number): Promise<T> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([work, new Promise<never>((_, reject) => (timer = setTimeout(() => reject(new Error('timeout')), ms)))]);
  } finally {
    clearTimeout(timer);
  }
}

/**
 * FCM v1 request body: a data-only message with identity fields, collapsed by event ID. The app posts the generic
 * text itself on the right channel, so a signed-out phone can stay silent and no provider-drawn copy exists.
 */
export function fcmMessage(token: string, message: PushMessage) {
  return {
    message: {
      token,
      data: { eventId: message.eventId, kind: message.kind, destinationId: message.destinationId },
      android: { priority: message.critical ? 'high' : 'normal', ttl: `${message.ttlSeconds}s`, collapse_key: message.eventId },
    },
  };
}

/** Maps an FCM v1 response to an outcome. Only the provider's fixed status names are kept, never its text. */
export async function fcmOutcome(response: Response): Promise<PushOutcome> {
  if (response.ok) {
    const body = (await response.json().catch(() => null)) as { name?: unknown } | null;
    const name = typeof body?.name === 'string' ? body.name : '';
    const id = /\/messages\/([A-Za-z0-9:_-]{1,200})$/.exec(name)?.[1];
    return id ? { outcome: 'sent', receipt: id } : { outcome: 'uncertain' };
  }
  const body = (await response.json().catch(() => null)) as { error?: { status?: unknown; details?: { errorCode?: unknown }[] } } | null;
  const codes = [body?.error?.status, ...(body?.error?.details ?? []).map((d) => d?.errorCode)].filter((c): c is string => typeof c === 'string');
  if (response.status === 404 || codes.includes('UNREGISTERED')) return { outcome: 'invalid_token' };
  if (response.status === 400 && codes.includes('INVALID_ARGUMENT')) return { outcome: 'rejected', code: 'provider_invalid_argument' };
  if (response.status === 401 || response.status === 403) return { outcome: 'rejected', code: 'provider_auth' };
  if (response.status === 429) return { outcome: 'rejected', code: 'provider_quota' };
  if (response.status >= 500) return { outcome: 'rejected', code: 'provider_unavailable' };
  return { outcome: 'rejected', code: 'provider_rejected' };
}

let cached: { email: string; token: string; expiresAt: number } | null = null;

export function fcmSender(serviceAccountJson: string, fetcher: Fetch = (input, init) => fetch(input, init), clock: () => number = Date.now): PushSender | null {
  const account = parseAccount(serviceAccountJson);
  if (!account) return null;

  async function accessToken(): Promise<string | null> {
    const now = clock();
    if (cached && cached.email === account!.client_email && cached.expiresAt > now + 60_000) return cached.token;
    const response = await withTimeout(
      fetcher(TOKEN_URL, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: await assertion(account!, now) }).toString(),
      }),
      SEND_TIMEOUT_MS,
    );
    if (!response.ok) return null;
    const body = (await response.json()) as { access_token?: unknown; expires_in?: unknown };
    if (typeof body.access_token !== 'string') return null;
    const ttl = typeof body.expires_in === 'number' ? body.expires_in : 3600;
    cached = { email: account!.client_email, token: body.access_token, expiresAt: now + ttl * 1000 };
    return body.access_token;
  }

  return {
    async send(token, message) {
      // A token failure happens before any send, so it is an authoritative no-send.
      let bearer: string | null;
      try {
        bearer = await accessToken();
      } catch {
        return { outcome: 'rejected', code: 'provider_auth_unavailable' };
      }
      if (!bearer) return { outcome: 'rejected', code: 'provider_auth' };
      let response: Response;
      try {
        response = await withTimeout(
          fetcher(`https://fcm.googleapis.com/v1/projects/${account.project_id}/messages:send`, {
            method: 'POST',
            headers: { authorization: `Bearer ${bearer}`, 'content-type': 'application/json' },
            body: JSON.stringify(fcmMessage(token, message)),
          }),
          SEND_TIMEOUT_MS,
        );
      } catch {
        // Lost response or timeout: the provider may have accepted the message.
        return { outcome: 'uncertain' };
      }
      if (response.status === 401) cached = null;
      return fcmOutcome(response);
    },
  };
}

/** Clears the in-memory OAuth token (tests). */
export function resetFcmCache(): void {
  cached = null;
}
