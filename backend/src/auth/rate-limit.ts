import { sha256Hex } from '../ids';
import { HttpError } from '../http/errors';

const HOUR_MS = 60 * 60 * 1000;

/** Counts an attempt for `scope` from this client and rejects past `limit` per hour, before expensive verification. */
export async function consumeAttempt(db: D1Database, request: Request, scope: string, limit: number, now: number): Promise<void> {
  const client = request.headers.get('cf-connecting-ip') ?? 'unknown';
  const key = `${scope}:${(await sha256Hex(client)).slice(0, 32)}`;
  const window = Math.floor(now / HOUR_MS) * HOUR_MS;
  const row = await db
    .prepare('INSERT INTO rate_limits(key, window_start, count) VALUES (?, ?, 1) ON CONFLICT(key, window_start) DO UPDATE SET count = count + 1 RETURNING count')
    .bind(key, window)
    .first<{ count: number }>();
  if ((row?.count ?? 0) > limit) {
    throw new HttpError(429, 'rate_limited', 'Too many attempts. Try again later.', { retryAfterSeconds: Math.ceil((window + HOUR_MS - now) / 1000) }, true);
  }
}
