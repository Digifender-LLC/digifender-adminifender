import { RELEASE_CONTRACT } from '../contracts';
import type { Env } from '../env';
import { fail, ok } from './envelope';

const PREFIX = '/api/v1';

export async function handleFetch(request: Request, _env: Env): Promise<Response> {
  const requestId = crypto.randomUUID();
  const { pathname } = new URL(request.url);
  if (!pathname.startsWith(`${PREFIX}/`)) return fail(404, 'not_found', 'Not found.', requestId);
  const route = pathname.slice(PREFIX.length);

  try {
    if (route === '/meta' && request.method === 'GET') {
      return ok({ contract: RELEASE_CONTRACT }, requestId);
    }
    return fail(404, 'not_found', 'Not found.', requestId);
  } catch {
    // Content-free: the cause is not echoed to the caller.
    return fail(503, 'unavailable', 'The service is temporarily unavailable.', requestId, { retryable: true });
  }
}
