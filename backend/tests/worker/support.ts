import { env } from 'cloudflare:workers';
import { validators } from 'virtual:contract-validators';
import type { Env, QueueEnvelope } from '../../src/env';
import { handleFetch } from '../../src/http/router';
import { type InboundEmail, admitEmail } from '../../src/admission/capture';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { runJob } from '../../src/jobs/runner';
import { FakeAuthenticator } from './fake-authenticator';
/** Captures queue sends so tests drive jobs explicitly instead of racing the local queue consumer. */
export function quietEnv(): Env & { sent: QueueEnvelope[] } {
  const sent: QueueEnvelope[] = [];
  const queue = {
    send: async (body: QueueEnvelope) => { sent.push(body); },
    sendBatch: async (messages: { body: QueueEnvelope }[]) => { sent.push(...messages.map((m) => m.body)); },
  } as unknown as Queue<QueueEnvelope>;
  return { ...env, WORK_QUEUE: queue, sent };
}


export const T0 = Date.UTC(2026, 9, 3, 12);
export const ORIGIN = 'http://localhost:8787';

export interface ApiResult {
  status: number;
  headers: Headers;
  body: { data?: any; error?: { code: string; details?: any }; requestId?: string };
}

export async function api(
  method: string,
  path: string,
  options: { body?: unknown; token?: string; auth?: string; now?: number; key?: string; raw?: boolean; env?: Env; extraHeaders?: Record<string, string> } = {},
): Promise<ApiResult> {
  const headers: Record<string, string> = { 'cf-connecting-ip': '192.0.2.10' };
  if (options.body !== undefined) headers['content-type'] = 'application/json';
  if (options.token) headers.authorization = `Bearer ${options.token}`;
  if (options.auth) headers.authorization = options.auth;
  if (options.key) headers['idempotency-key'] = options.key;
  Object.assign(headers, options.extraHeaders);
  const request = new Request(`https://api.example.invalid/api/v1${path}`, { method, headers, body: options.body === undefined ? null : JSON.stringify(options.body) });
  const response = await handleFetch(request, options.env ?? env, options.now ?? T0);
  const isJson = response.headers.get('content-type')?.startsWith('application/json');
  return { status: response.status, headers: response.headers, body: isJson ? await response.json() : { data: new Uint8Array(await response.arrayBuffer()) } };
}

async function hex(text: string): Promise<string> {
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)))].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Enrolls the owner through the real bootstrap flow and returns an access token. */
export async function enrollOwner(now = T0): Promise<{ token: string; authenticator: FakeAuthenticator; recoverySecret: string; refreshToken: string; deviceId: string }> {
  const bootstrap = `afb_${'Q'.repeat(43)}`;
  await env.CONTROL_DB.prepare("INSERT INTO auth_challenges(id, kind, challenge_digest, context_json, expires_at) VALUES (?, 'bootstrap', ?, '{}', ?)")
    .bind(crypto.randomUUID(), await hex(bootstrap), now + 900_000)
    .run();
  const authenticator = new FakeAuthenticator('localhost', ORIGIN);
  const options = await api('POST', '/auth/bootstrap/options', { body: { bootstrapToken: bootstrap, deviceName: 'Test phone' }, now });
  const credential = await authenticator.register(options.body.data.options);
  const verified = await api('POST', '/auth/bootstrap/verify', { body: { challengeId: options.body.data.challengeId, credential }, now });
  if (verified.status !== 200) throw new Error(`enroll failed ${verified.status}`);
  const data = verified.body.data;
  return { token: data.accessToken, authenticator, recoverySecret: data.recoverySecret, refreshToken: data.refreshToken, deviceId: data.deviceId };
}

/** Stages raw MIME and runs its parse job, returning the published source ID. */
export async function ingest(raw: string, options: { to?: string; now?: number; env?: Partial<Env> } = {}): Promise<string> {
  const bytes = new TextEncoder().encode(raw);
  const message: InboundEmail = {
    from: 'sender@example.com',
    to: options.to ?? 'inbox@adminifender.example',
    headers: new Headers(),
    rawSize: bytes.byteLength,
    get raw() {
      return new Response(bytes).body as ReadableStream<Uint8Array>;
    },
    setReject() {
      throw new Error('unexpected rejection');
    },
  };
  const quiet = Object.assign(quietEnv(), options.env ?? {});
  const outcome = await admitEmail(message, quiet, options.now ?? T0);
  if (outcome.kind !== 'staged') throw new Error(outcome.kind);
  const run = await runJob(quiet, JOB_HANDLERS, outcome.jobId, options.now ?? T0);
  if (run.status !== 'complete') throw new Error(run.status);
  return outcome.sourceId;
}

/** Runs every due job of one kind, oldest first, and returns their outcomes. `extra` adds bindings such as a fake AI. */
export async function runPending(kind: string, now = T0, extra: Partial<Env> = {}): Promise<string[]> {
  const { results } = await env.CONTROL_DB.prepare("SELECT id FROM jobs WHERE kind = ? AND state = 'pending' AND next_at <= ? ORDER BY created_at, id").bind(kind, now).all<{ id: string }>();
  const statuses: string[] = [];
  for (const row of results) statuses.push((await runJob({ ...quietEnv(), ...extra }, JOB_HANDLERS, row.id, now)).status);
  return statuses;
}

/** Access tokens for a session as the test clock moves on, refreshing once the current one may have expired. */
export function sessionClock(owner: { token: string; refreshToken: string }, start = T0): (now: number) => Promise<string> {
  let token = owner.token;
  let refresh = owner.refreshToken;
  let issued = start;
  return async (now) => {
    if (now - issued < 5 * 60_000) return token;
    const refreshed = await api('POST', '/auth/refresh', { auth: `Refresh ${refresh}`, now });
    if (refreshed.status !== 200) throw new Error(`refresh failed ${refreshed.status}`);
    token = refreshed.body.data.accessToken;
    refresh = refreshed.body.data.refreshToken;
    issued = now;
    return token;
  };
}

/** The real JSON Schema of a shared contract, compiled in Node by vitest.config.ts. */
export function contractValidator(name: string) {
  const validate = validators[name];
  if (!validate) throw new Error(`no schema named ${name}`);
  return validate;
}

/** Fails with the schema's own error list when a live response does not match its shared contract. */
export function expectContract(name: string, data: unknown): void {
  const validate = contractValidator(name);
  if (!validate(data)) throw new Error(`${name} contract: ${JSON.stringify(validate.errors)} for ${JSON.stringify(data).slice(0, 400)}`);
}

/** The negative twin: the contract must refuse this shape. */
export function expectContractViolation(name: string, data: unknown): void {
  if (contractValidator(name)(data)) throw new Error(`${name} contract unexpectedly accepted ${JSON.stringify(data).slice(0, 400)}`);
}
