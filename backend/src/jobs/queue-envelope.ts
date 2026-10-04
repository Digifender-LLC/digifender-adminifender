import { isUuidV4 } from '../contracts';
import type { QueueEnvelope } from '../env';

export type ParsedEnvelope = { ok: true; value: QueueEnvelope } | { ok: false; reason: 'shape' | 'version' | 'job_id' };

/** Mirrors contracts/schema/v1/queue-envelope.schema.json without runtime code generation. */
export function parseQueueEnvelope(body: unknown): ParsedEnvelope {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) return { ok: false, reason: 'shape' };
  const keys = Object.keys(body);
  if (keys.length !== 2 || !keys.includes('version') || !keys.includes('jobId')) return { ok: false, reason: 'shape' };
  const { version, jobId } = body as Record<string, unknown>;
  if (version !== 1) return { ok: false, reason: 'version' };
  if (!isUuidV4(jobId)) return { ok: false, reason: 'job_id' };
  return { ok: true, value: { version, jobId } };
}
