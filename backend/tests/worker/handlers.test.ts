import { createMessageBatch, getQueueResult } from 'cloudflare:test';
import { createExecutionContext } from 'cloudflare:test';
import { env, exports } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import worker from '../../src/index';
import { parseQueueEnvelope } from '../../src/jobs/queue-envelope';

const fixtures = import.meta.glob<{ default: unknown }>('../../../contracts/fixtures/v1/queue-envelope/*/*.json', { eager: true });

describe('HTTP envelope', () => {
  it('serves the release contract manifest', async () => {
    const response = await exports.default.fetch('https://example.invalid/api/v1/meta');
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('no-store');
    const body = await response.json<{ data: { contract: Record<string, number> }; requestId: string }>();
    expect(body.data.contract).toEqual({ httpSchemaVersion: 1, eventSchemaVersion: 1, controlMigration: 9, archiveMigration: 1 });
    expect(body.requestId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('returns the error envelope for unknown routes', async () => {
    for (const path of ['/', '/api/v1/missing', '/api/v2/meta']) {
      const response = await exports.default.fetch(`https://example.invalid${path}`);
      expect(response.status).toBe(404);
      const body = await response.json<{ error: { code: string; retryable: boolean } }>();
      expect(body.error).toEqual({ code: 'not_found', message: 'Not found.', retryable: false, details: {} });
    }
  });
});

describe('queue envelope', () => {
  it('agrees with every shared contract fixture', () => {
    const entries = Object.entries(fixtures);
    expect(entries.length).toBeGreaterThan(0);
    for (const [path, module] of entries) {
      expect(parseQueueEnvelope(module.default).ok, path).toBe(path.includes('/valid/'));
    }
  });

  it('acknowledges valid and malformed messages without retry loops', async () => {
    const batch = createMessageBatch('adminifender-work-local', [
      { id: 'm1', timestamp: new Date(), attempts: 1, body: { version: 1, jobId: '9b2e7c1d-3a4f-4e5b-8c6d-7e8f9a0b1c2d' } },
      { id: 'm2', timestamp: new Date(), attempts: 1, body: { version: 1, jobId: 'x', body: 'leaked content' } },
    ]);
    const ctx = createExecutionContext();
    await worker.queue(batch, env, ctx);
    const result = await getQueueResult(batch, ctx);
    expect(result.explicitAcks.sort()).toEqual(['m1', 'm2']);
    expect(result.retryMessages).toEqual([]);
  });
});
