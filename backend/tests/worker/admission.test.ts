import { env as workerEnv } from 'cloudflare:workers';
import { quietEnv } from './support';

const env = quietEnv();
import { beforeEach, describe, expect, it } from 'vitest';
import { type InboundEmail, admitEmail } from '../../src/admission/capture';
import worker from '../../src/index';

const now = Date.UTC(2026, 9, 3, 12);
const rawMail = (body = 'Synthetic test body.') =>
  `From: Sender <sender@example.com>\r\nTo: inbox@adminifender.example\r\nSubject: Test\r\nMessage-ID: <1@example.com>\r\n\r\n${body}\r\n`;

interface FakeMail extends InboundEmail {
  rejected: string | null;
  bodyRead: boolean;
}

function mail(options: { from?: string; header?: string; to?: string; raw?: string; rawSize?: number } = {}): FakeMail {
  const bytes = new TextEncoder().encode(options.raw ?? rawMail());
  const fake: FakeMail = {
    from: options.from ?? 'sender@example.com',
    to: options.to ?? 'inbox@adminifender.example',
    headers: new Headers({ from: options.header ?? 'Sender <sender@example.com>' }),
    rawSize: options.rawSize ?? bytes.byteLength,
    rejected: null,
    bodyRead: false,
    get raw() {
      fake.bodyRead = true;
      return new Response(bytes).body as ReadableStream<Uint8Array>;
    },
    setReject(reason: string) {
      fake.rejected = reason;
    },
  };
  return fake;
}

async function addRule(id: string, kind: 'address' | 'domain', matchField: string, value: string, enabled = 1) {
  await env.CONTROL_DB.prepare('INSERT INTO block_rules(id, revision, kind, match_field, normalized_value, enabled, created_at) VALUES (?, 1, ?, ?, ?, ?, ?)')
    .bind(id, kind, matchField, value, enabled, now)
    .run();
}

const count = async (sql: string) => (await env.CONTROL_DB.prepare(sql).first<{ n: number }>())?.n ?? 0;
const stagedObjects = async () => (await env.ARCHIVE_BUCKET.list({ prefix: 'staging/' })).objects;

describe('manual blocking', () => {
  beforeEach(async () => {
    await addRule('rule-domain', 'domain', 'either', 'spam.example');
  });

  it('rejects before reading the body and keeps only an aggregate counter', async () => {
    const message = mail({ from: 'promo@spam.example', header: 'Promo <promo@spam.example>' });
    const outcome = await admitEmail(message, env, now);
    expect(outcome).toEqual({ kind: 'blocked', ruleId: 'rule-domain' });
    expect(message.rejected).toBe('Message rejected by recipient policy.');
    expect(message.bodyRead).toBe(false);
    expect(await stagedObjects()).toEqual([]);
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(0);
    await admitEmail(mail({ from: 'other@spam.example' }), env, now);
    const counter = await env.CONTROL_DB.prepare('SELECT rule_id, day, count FROM block_counters').all();
    expect(counter.results).toEqual([{ rule_id: 'rule-domain', day: '2026-10-03', count: 2 }]);
    const columns = (await env.CONTROL_DB.prepare('PRAGMA table_info(block_counters)').all<{ name: string }>()).results.map((c) => c.name);
    expect(columns).toEqual(['rule_id', 'day', 'count']);
  });

  it('blocks on a matching header From under either-field rules', async () => {
    const outcome = await admitEmail(mail({ from: 'bounce@relay.example', header: 'x@spam.example' }), env, now);
    expect(outcome.kind).toBe('blocked');
  });

  it('accepts mail once the rule is disabled', async () => {
    await env.CONTROL_DB.prepare("UPDATE block_rules SET enabled = 0 WHERE id = 'rule-domain'").run();
    const outcome = await admitEmail(mail({ from: 'promo@spam.example' }), env, now);
    expect(outcome.kind).toBe('staged');
  });
});

describe('durable staging', () => {
  it('stages raw bytes, then registers the source, parse job and outbox together', async () => {
    const message = mail();
    const outcome = await admitEmail(message, env, now);
    if (outcome.kind !== 'staged') throw new Error(`unexpected ${outcome.kind}`);
    expect(message.rejected).toBeNull();

    const source = await env.CONTROL_DB.prepare('SELECT * FROM source_registry WHERE id = ?').bind(outcome.sourceId).first<Record<string, unknown>>();
    expect(source).toMatchObject({ kind: 'email', admission: 'normal', privacy: 'hybrid', state: 'staged', revision: 1, received_at: now, partition_id: null });
    const object = await env.ARCHIVE_BUCKET.get(source?.staged_key as string);
    expect(await object?.text()).toBe(rawMail());
    expect(source?.staged_key).toBe(`staging/${outcome.sourceId}/${source?.content_hash}.eml`);
    expect(Object.keys(object?.customMetadata ?? {}).sort()).toEqual(['arrivalId', 'receivedAt', 'recipientHash']);

    const job = await env.CONTROL_DB.prepare('SELECT kind, state, source_id, input_json FROM jobs WHERE id = ?').bind(outcome.jobId).first();
    expect(job).toEqual({ kind: 'parse', state: 'pending', source_id: outcome.sourceId, input_json: JSON.stringify({ sourceId: outcome.sourceId }) });
    expect(await count(`SELECT COUNT(*) AS n FROM outbox WHERE reference_id = '${outcome.jobId}' AND state = 'sent'`)).toBe(1);
  });

  it('deduplicates the same delivery to the same recipient only', async () => {
    const first = await admitEmail(mail(), env, now);
    const repeat = await admitEmail(mail(), env, now);
    const otherRecipient = await admitEmail(mail({ to: 'alias@adminifender.example' }), env, now);
    if (first.kind !== 'staged') throw new Error(first.kind);
    expect(repeat).toEqual({ kind: 'duplicate', sourceId: first.sourceId });
    expect(otherRecipient.kind).toBe('staged');
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(2);
    expect(await count('SELECT COUNT(*) AS n FROM jobs')).toBe(2);
    expect((await stagedObjects()).length).toBe(2);
  });

  it('treats a missing Message-ID or changed bytes as a distinct delivery', async () => {
    await admitEmail(mail(), env, now);
    const changed = await admitEmail(mail({ raw: rawMail('Different body.') }), env, now);
    expect(changed.kind).toBe('staged');
  });

  it('rejects oversize mail without reading it', async () => {
    const message = mail({ rawSize: 25 * 1024 * 1024 + 1 });
    expect(await admitEmail(message, env, now)).toEqual({ kind: 'too_large' });
    expect(message.bodyRead).toBe(false);
  });

  it('bounces visibly and registers nothing when staging fails', async () => {
    const failingBucket = { put: () => Promise.reject(new Error('r2 down')) } as unknown as R2Bucket;
    const message = mail();
    const outcome = await admitEmail(message, { ...env, ARCHIVE_BUCKET: failingBucket }, now);
    expect(outcome).toEqual({ kind: 'failed', stage: 'stage' });
    expect(message.rejected).toMatch(/Temporary failure/);
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(0);
    expect(await count("SELECT COUNT(*) AS n FROM audit_events WHERE kind = 'ingest' AND outcome = 'failed'")).toBe(1);
  });

  it('keeps the staged object discoverable when registration fails', async () => {
    const failingDb = new Proxy(env.CONTROL_DB, {
      get(target, property) {
        if (property === 'batch') return () => Promise.reject(new Error('d1 down'));
        const value = Reflect.get(target, property);
        return typeof value === 'function' ? value.bind(target) : value;
      },
    });
    const message = mail();
    const outcome = await admitEmail(message, { ...env, CONTROL_DB: failingDb }, now);
    expect(outcome).toEqual({ kind: 'failed', stage: 'register' });
    expect(message.rejected).toMatch(/Temporary failure/);
    const objects = await stagedObjects();
    expect(objects.length).toBe(1);
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(0);
  });

  it('leaves the outbox pending when queue publication fails', async () => {
    const failingQueue = { send: () => Promise.reject(new Error('queue down')) } as unknown as Queue<{ version: 1; jobId: string }>;
    const outcome = await admitEmail(mail(), { ...workerEnv, WORK_QUEUE: failingQueue }, now);
    expect(outcome.kind).toBe('staged');
    expect(await count("SELECT COUNT(*) AS n FROM outbox WHERE state = 'pending'")).toBe(1);
  });
});

describe('staging forward copy', () => {
  const ctx = {} as ExecutionContext;
  function forwardable(failForward = false) {
    const forwarded: string[] = [];
    const message = Object.assign(mail(), {
      forward: async (to: string) => {
        if (failForward) throw new Error('destination not verified');
        forwarded.push(to);
      },
    });
    return { message: message as unknown as ForwardableEmailMessage & FakeMail, forwarded };
  }

  it('forwards newly stored mail once and never forwards duplicates', async () => {
    const forwardEnv = { ...env, FORWARD_TO: 'copy@example.com' };
    const first = forwardable();
    await worker.email!(first.message, forwardEnv, ctx);
    const repeat = forwardable();
    await worker.email!(repeat.message, forwardEnv, ctx);
    expect(first.forwarded).toEqual(['copy@example.com']);
    expect(repeat.forwarded).toEqual([]);
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(1);
  });

  it('does not forward without FORWARD_TO, and keeps the stored copy when forwarding fails', async () => {
    const unset = forwardable();
    await worker.email!(unset.message, env, ctx);
    expect(unset.forwarded).toEqual([]);
    const failing = forwardable(true);
    Object.assign(failing.message, { to: 'alias@adminifender.example' });
    await worker.email!(failing.message, { ...env, FORWARD_TO: 'copy@example.com' }, ctx);
    expect(failing.message.rejected).toBeNull();
    expect(await count('SELECT COUNT(*) AS n FROM source_registry')).toBe(2);
  });

  it('does not forward rejected mail', async () => {
    const { message, forwarded } = forwardable();
    Object.assign(message, { rawSize: 25 * 1024 * 1024 + 1 });
    await worker.email!(message, { ...env, FORWARD_TO: 'copy@example.com' }, ctx);
    expect(message.rejected).not.toBeNull();
    expect(forwarded).toEqual([]);
  });
});
