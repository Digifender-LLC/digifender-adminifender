import { sha256Hex } from '../ids';
import { HttpError } from './errors';

/** Mutation identity retained 90 days (baseline.md defaults). */
export const MUTATION_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
/** A pending key older than this is treated as an abandoned attempt; revision checks still stop a double apply. */
const PENDING_STALE_MS = 5 * 60 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

/** Stable JSON with sorted object keys, so equivalent bodies hash the same. */
export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value as object).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson((value as Record<string, unknown>)[key])}`).join(',')}}`;
  }
  return JSON.stringify(value ?? null);
}

export interface StoredResult {
  status: number;
  body: unknown;
}

/**
 * Same key + method + path + canonical body returns the retained result; different content returns
 * 409 idempotency_conflict (lld.md#contract-conventions).
 */
export async function withIdempotency(
  db: D1Database,
  request: Request,
  path: string,
  body: unknown,
  now: number,
  run: () => Promise<StoredResult>,
): Promise<StoredResult> {
  const key = request.headers.get('idempotency-key') ?? '';
  if (!UUID.test(key)) throw new HttpError(400, 'invalid_request', 'An Idempotency-Key UUID is required.', { reason: 'idempotency_key' });
  const requestHash = await sha256Hex(`${request.method}\n${path}\n${canonicalJson(body)}`);
  const existing = await db.prepare('SELECT request_hash, state, result_json, created_at FROM mutations WHERE key = ? AND expires_at > ?').bind(key, now).first<{
    request_hash: string;
    state: string;
    result_json: string | null;
    created_at: number;
  }>();
  if (existing) {
    if (existing.request_hash !== requestHash) throw new HttpError(409, 'idempotency_conflict', 'This key was used for a different request.');
    if (existing.state === 'complete' && existing.result_json) return JSON.parse(existing.result_json) as StoredResult;
    if (now - existing.created_at < PENDING_STALE_MS) throw new HttpError(409, 'request_in_progress', 'This request is still being processed.', {}, true);
  }
  await db
    .prepare("INSERT INTO mutations(key, request_hash, state, created_at, expires_at) VALUES (?, ?, 'pending', ?, ?) ON CONFLICT(key) DO UPDATE SET created_at = excluded.created_at WHERE mutations.state = 'pending' AND mutations.request_hash = excluded.request_hash")
    .bind(key, requestHash, now, now + MUTATION_RETENTION_MS)
    .run();
  let result: StoredResult;
  try {
    result = await run();
  } catch (error) {
    // Client errors are deterministic for this body, so they are retained like successes; transient ones are not.
    if (error instanceof HttpError && error.status < 500 && error.status !== 429 && error.code !== 'request_in_progress') {
      result = { status: error.status, body: { error: { code: error.code, message: error.message, retryable: error.retryable, details: error.details } } };
    } else {
      await db.prepare("DELETE FROM mutations WHERE key = ? AND state = 'pending'").bind(key).run();
      throw error;
    }
  }
  await db.prepare("UPDATE mutations SET state = 'complete', result_json = ? WHERE key = ?").bind(JSON.stringify(result), key).run();
  return result;
}
