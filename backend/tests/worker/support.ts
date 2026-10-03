import { env } from 'cloudflare:workers';
import type { Env, QueueEnvelope } from '../../src/env';

/** Captures queue sends so tests drive jobs explicitly instead of racing the local queue consumer. */
export function quietEnv(): Env & { sent: QueueEnvelope[] } {
  const sent: QueueEnvelope[] = [];
  const queue = {
    send: async (body: QueueEnvelope) => { sent.push(body); },
    sendBatch: async (messages: { body: QueueEnvelope }[]) => { sent.push(...messages.map((m) => m.body)); },
  } as unknown as Queue<QueueEnvelope>;
  return { ...env, WORK_QUEUE: queue, sent };
}
