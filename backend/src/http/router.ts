import * as auth from '../auth/routes';
import { RELEASE_CONTRACT } from '../contracts';
import type { Env } from '../env';
import { fail, ok } from './envelope';
import { HttpError } from './errors';

const PREFIX = '/api/v1';

type Handler = (request: Request, env: Env, now: number, params: string[]) => Promise<unknown>;
interface Route {
  method: 'GET' | 'POST' | 'PATCH';
  pattern: RegExp;
  handler: Handler;
}

const ROUTES: Route[] = [
  { method: 'GET', pattern: /^\/meta$/, handler: async () => ({ contract: RELEASE_CONTRACT }) },
  { method: 'POST', pattern: /^\/auth\/bootstrap\/options$/, handler: auth.bootstrapOptions },
  { method: 'POST', pattern: /^\/auth\/bootstrap\/verify$/, handler: auth.bootstrapVerify },
  { method: 'POST', pattern: /^\/auth\/passkey\/options$/, handler: auth.passkeyOptions },
  { method: 'POST', pattern: /^\/auth\/passkey\/verify$/, handler: auth.passkeyVerify },
  { method: 'POST', pattern: /^\/auth\/refresh$/, handler: auth.refresh },
  { method: 'POST', pattern: /^\/auth\/recovery\/options$/, handler: auth.recoveryOptions },
  { method: 'POST', pattern: /^\/auth\/recovery\/verify$/, handler: auth.recoveryVerify },
  { method: 'POST', pattern: /^\/auth\/logout$/, handler: auth.logout },
  { method: 'GET', pattern: /^\/devices$/, handler: auth.listDevices },
];

export async function handleFetch(request: Request, env: Env, now = Date.now()): Promise<Response> {
  const requestId = crypto.randomUUID();
  const { pathname } = new URL(request.url);
  if (!pathname.startsWith(`${PREFIX}/`)) return fail(404, 'not_found', 'Not found.', requestId);
  const path = pathname.slice(PREFIX.length);

  try {
    for (const route of ROUTES) {
      const match = route.pattern.exec(path);
      if (match && route.method === request.method) return ok(await route.handler(request, env, now, match.slice(1)), requestId);
    }
    return fail(404, 'not_found', 'Not found.', requestId);
  } catch (error) {
    if (error instanceof HttpError) {
      const response = fail(error.status, error.code, error.message, requestId, { retryable: error.retryable, details: error.details });
      const retryAfter = error.details.retryAfterSeconds;
      if (error.status === 429 && typeof retryAfter === 'number') response.headers.set('retry-after', String(retryAfter));
      return response;
    }
    // Content-free: the cause is not echoed to the caller.
    console.error(JSON.stringify({ event: 'http_unhandled' }));
    return fail(503, 'unavailable', 'The service is temporarily unavailable.', requestId, { retryable: true });
  }
}
