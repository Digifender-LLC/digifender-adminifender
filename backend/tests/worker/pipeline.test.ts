import { env as workerEnv } from 'cloudflare:workers';
import { quietEnv } from './support';

const env = quietEnv();
import { describe, expect, it } from 'vitest';
import { type InboundEmail, admitEmail } from '../../src/admission/capture';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { reconcile } from '../../src/jobs/reconcile';
import { LEASE_MS, MAX_ATTEMPTS, claimJob, retryDelay, runJob } from '../../src/jobs/runner';

const now = Date.UTC(2026, 9, 3, 12);
const mimeFixtures = import.meta.glob<string>('../../../contracts/fixtures/mime/*.eml', { query: '?raw', import: 'default', eager: true });
const fixture = (name: string) => mimeFixtures[`../../../contracts/fixtures/mime/${name}.eml`] as string;

function inbound(raw: string, to = 'inbox@adminifender.example'): InboundEmail {
  const bytes = new TextEncoder().encode(raw);
  return {
    from: 'sender@example.com',
    to,
    headers: new Headers(),
    rawSize: bytes.byteLength,
    get raw() {
      return new Response(bytes).body as ReadableStream<Uint8Array>;
    },
    setReject() {
      throw new Error('unexpected rejection');
    },
  };
}

async function stage(raw: string, to?: string) {
  const outcome = await admitEmail(inbound(raw, to), env, now);
  if (outcome.kind !== 'staged') throw new Error(outcome.kind);
  return outcome;
}

const source = (id: string) => env.CONTROL_DB.prepare('SELECT state, admission, partition_id, ready_seq, revision FROM source_registry WHERE id = ?').bind(id).first<Record<string, unknown>>();
const job = (id: string) => env.CONTROL_DB.prepare('SELECT state, attempt, error_code, next_at FROM jobs WHERE id = ?').bind(id).first<Record<string, unknown>>();

describe('parse job and archive publication', () => {
  it('publishes a ready, searchable source with attachments and a receipt', async () => {
    const { sourceId, jobId } = await stage(fixture('attachments-no-message-id'));
    expect(await runJob(env, JOB_HANDLERS, jobId, now)).toEqual({ jobId, status: 'complete' });

    expect(await source(sourceId)).toMatchObject({ state: 'ready', admission: 'normal', partition_id: 1, revision: 2 });
    const change = await env.CONTROL_DB.prepare("SELECT seq, projection_json FROM changes WHERE entity_type = 'source' AND entity_id = ?").bind(sourceId).first<{ seq: number; projection_json: string }>();
    expect((await source(sourceId))?.ready_seq).toBe(change?.seq);
    expect(JSON.parse(change?.projection_json ?? '{}')).toMatchObject({ id: sourceId, state: 'ready', admission: 'normal' });

    const archived = await env.ARCHIVE_0001.prepare('SELECT subject, normalized_text, provenance_json FROM sources WHERE id = ?').bind(sourceId).first<Record<string, string>>();
    expect(archived?.subject).toBe('Report attached');
    expect(JSON.parse(archived?.provenance_json ?? '{}').admission).toEqual({ detector: 'spam-rules-v1', score: 0, features: [] });

    const files = await env.ARCHIVE_0001.prepare('SELECT name, sha256, object_key FROM attachments WHERE source_id = ? ORDER BY name').bind(sourceId).all<{ name: string; sha256: string; object_key: string }>();
    expect(files.results.map((f) => f.name)).toEqual(['chart.png', 'report.pdf']);
    for (const file of files.results) {
      const object = await env.ARCHIVE_BUCKET.get(file.object_key);
      const digest = await crypto.subtle.digest('SHA-256', await object!.arrayBuffer());
      expect([...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')).toBe(file.sha256);
    }
    const processing = await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM attachment_processing WHERE source_id = ? AND state = 'stored'").bind(sourceId).first<{ n: number }>();
    expect(processing?.n).toBe(2);

    const hit = await env.ARCHIVE_0001.prepare("SELECT source_id FROM source_fts WHERE source_fts MATCH '\"report\" AND \"pdf\"'").first<{ source_id: string }>();
    expect(hit?.source_id).toBe(sourceId);
    expect(await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM archive_receipts WHERE source_id = ?').bind(sourceId).first()).toEqual({ n: 1 });
  });

  it('is idempotent when the same job is delivered again', async () => {
    const { sourceId, jobId } = await stage(fixture('plain-text'));
    await runJob(env, JOB_HANDLERS, jobId, now);
    expect(await runJob(env, JOB_HANDLERS, jobId, now + 1)).toEqual({ jobId, status: 'skipped' });
    const counts = await env.ARCHIVE_0001.prepare('SELECT (SELECT COUNT(*) FROM sources) AS s, (SELECT COUNT(*) FROM chunks) AS c, (SELECT COUNT(*) FROM source_fts) AS f').first();
    expect(counts).toEqual({ s: 1, c: 1, f: 1 });
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM changes WHERE entity_id = ?').bind(sourceId).first()).toEqual({ n: 1 });
  });

  it('recovers when archive rows exist from a crashed earlier attempt', async () => {
    const { sourceId, jobId } = await stage(fixture('plain-text'));
    const first = await claimJob(env.CONTROL_DB, jobId, now);
    await JOB_HANDLERS.parse!(first!, env, now); // archive writes happen, control settle never does
    expect((await source(sourceId))?.state).toBe('staged');
    expect(await runJob(env, JOB_HANDLERS, jobId, now + LEASE_MS + 1)).toEqual({ jobId, status: 'complete' });
    expect((await source(sourceId))?.state).toBe('ready');
    expect(await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM chunks').first()).toEqual({ n: 1 });
  });

  it('quarantines suspected spam quietly and retains its full text', async () => {
    const raw = 'From: a@x.example, b@y.example\r\nTo: inbox@adminifender.example\r\nSubject: Prize\r\nContent-Type: text/html\r\n\r\n<p>Claim your prize now.</p><a href="https://evil.example/">bank.example</a>\r\n';
    const { sourceId, jobId } = await stage(raw);
    await runJob(env, JOB_HANDLERS, jobId, now);
    expect(await source(sourceId)).toMatchObject({ state: 'ready', admission: 'quarantine' });
    const text = await env.ARCHIVE_0001.prepare('SELECT normalized_text FROM sources WHERE id = ?').bind(sourceId).first<{ normalized_text: string }>();
    expect(text?.normalized_text).toContain('Claim your prize now.');
    expect(await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE kind <> 'parse'").first()).toEqual({ n: 0 });
  });

  it('marks invalid MIME parse_failed and keeps the staged bytes', async () => {
    const { sourceId, jobId } = await stage(fixture('invalid-nesting'));
    expect(await runJob(env, JOB_HANDLERS, jobId, now)).toEqual({ jobId, status: 'failed' });
    expect(await source(sourceId)).toMatchObject({ state: 'parse_failed' });
    expect(await job(jobId)).toMatchObject({ state: 'failed', error_code: 'parse_error' });
    const staged = await env.ARCHIVE_BUCKET.list({ prefix: `staging/${sourceId}/` });
    expect(staged.objects.length).toBe(1);
  });

  it('holds the source pending_index while no partition is writable', async () => {
    await env.CONTROL_DB.prepare("UPDATE archive_partitions SET state = 'sealed' WHERE id = 1").run();
    const { sourceId, jobId } = await stage(fixture('plain-text'));
    expect(await runJob(env, JOB_HANDLERS, jobId, now)).toEqual({ jobId, status: 'retry' });
    expect(await source(sourceId)).toMatchObject({ state: 'pending_index', partition_id: null });
    expect(await job(jobId)).toMatchObject({ state: 'pending', next_at: now + retryDelay(1) });
  });

  it('stores large bodies in R2 with bounded chunks', async () => {
    const paragraph = 'Synthetic large body line for bounded storage. '.repeat(40);
    const body = Array.from({ length: 160 }, () => paragraph).join('\n\n');
    const { sourceId, jobId } = await stage(`From: a@example.com\r\nTo: inbox@adminifender.example\r\nSubject: Big\r\n\r\n${body}\r\n`);
    await runJob(env, JOB_HANDLERS, jobId, now);
    const row = await env.ARCHIVE_0001.prepare('SELECT normalized_text, text_object_key FROM sources WHERE id = ?').bind(sourceId).first<{ normalized_text: string | null; text_object_key: string }>();
    expect(row?.normalized_text).toBeNull();
    expect((await env.ARCHIVE_BUCKET.get(row!.text_object_key))?.size).toBeGreaterThan(256 * 1024);
    const longest = await env.ARCHIVE_0001.prepare('SELECT MAX(LENGTH(text)) AS n FROM chunks WHERE source_id = ?').bind(sourceId).first<{ n: number }>();
    expect(longest?.n).toBeLessThanOrEqual(6000);
  });
});

describe('job runner fencing and retries', () => {
  it('lets only one claimant hold a job', async () => {
    const { jobId } = await stage(fixture('plain-text'));
    const a = await claimJob(env.CONTROL_DB, jobId, now);
    const b = await claimJob(env.CONTROL_DB, jobId, now);
    expect(a).not.toBeNull();
    expect(b).toBeNull();
  });

  it('discards a stale claimant result after the lease is taken over', async () => {
    const { sourceId, jobId } = await stage(fixture('plain-text'));
    const stale = await claimJob(env.CONTROL_DB, jobId, now);
    const fresh = await claimJob(env.CONTROL_DB, jobId, now + LEASE_MS + 1);
    expect(fresh?.attempt).toBe(2);
    const staleResult = await JOB_HANDLERS.parse!(stale!, env, now);
    if (staleResult.kind !== 'complete') throw new Error(staleResult.kind);
    await env.CONTROL_DB.batch(staleResult.statements ?? []);
    expect((await source(sourceId))?.state).toBe('staged');
  });

  it('backs off exponentially and dead-letters after five attempts', async () => {
    expect([1, 2, 3, 4, 5, 10].map(retryDelay)).toEqual([30_000, 60_000, 120_000, 240_000, 480_000, 3_600_000]);
    const failing = { parse: async () => ({ kind: 'retry' as const, errorCode: 'synthetic' }) };
    const { jobId } = await stage(fixture('plain-text'));
    let t = now;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
      const outcome = await runJob(env, failing, jobId, t);
      expect(outcome.status).toBe(attempt < MAX_ATTEMPTS ? 'retry' : 'dead');
      t += retryDelay(attempt);
    }
    expect(await job(jobId)).toMatchObject({ state: 'dead', attempt: 5, error_code: 'synthetic' });
  });

  it('fails unknown job kinds visibly instead of looping', async () => {
    await env.CONTROL_DB.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES ('7c1d9b2e-3a4f-4e5b-8c6d-7e8f9a0b1c2d', 'x', 'mystery', 'pending', 0, '{}', 0)").run();
    expect((await runJob(env, JOB_HANDLERS, '7c1d9b2e-3a4f-4e5b-8c6d-7e8f9a0b1c2d', now)).status).toBe('failed');
  });
});

describe('scheduled reconciler', () => {
  it('publishes pending outbox rows and due jobs once each', async () => {
    const sent: unknown[] = [];
    const queue = { send: () => Promise.reject(new Error('down')), sendBatch: async (messages: { body: unknown }[]) => { sent.push(...messages.map((m) => m.body)); } } as unknown as Queue;
    const { jobId } = await stage(fixture('plain-text'));
    await admitEmail(inbound(fixture('html-only')), { ...workerEnv, WORK_QUEUE: queue }, now);
    const result = await reconcile({ CONTROL_DB: env.CONTROL_DB, WORK_QUEUE: queue }, now);
    expect(result.published).toBe(2);
    expect(sent).toContainEqual({ version: 1, jobId });
    expect(await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM outbox WHERE state = 'pending'").first()).toEqual({ n: 0 });
  });

  it('ignores jobs that are not yet due', async () => {
    const queue = { sendBatch: async () => { throw new Error('should not publish'); } } as unknown as Queue;
    await env.CONTROL_DB.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES ('j', 'k', 'parse', 'pending', ?, '{}', 0)").bind(now + 1000).run();
    expect(await reconcile({ CONTROL_DB: env.CONTROL_DB, WORK_QUEUE: queue }, now)).toEqual({ published: 0 });
  });
});
