import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { CURSOR_TTL_MS } from '../../src/http/cursor';
import { T0, api, enrollOwner, ingest } from './support';

const fixtures = import.meta.glob<string>('../../../contracts/fixtures/mime/*.eml', { query: '?raw', import: 'default', eager: true });
const fixture = (name: string) => fixtures[`../../../contracts/fixtures/mime/${name}.eml`] as string;
const spam = 'From: a@x.example, b@y.example\r\nTo: inbox@adminifender.example\r\nSubject: Prize invoice\r\nContent-Type: text/html\r\n\r\n<p>Claim your prize invoice now.</p><a href="https://evil.example/">bank.example</a>\r\n';
const mail = (subject: string, body: string, from = 'ops@example.com') => `From: ${from}\r\nTo: inbox@adminifender.example\r\nSubject: ${subject}\r\n\r\n${body}\r\n`;

let token: string;
let owner: Awaited<ReturnType<typeof enrollOwner>>;
beforeEach(async () => {
  owner = await enrollOwner();
  token = owner.token;
});

describe('authorization', () => {
  it('requires a session for every archive read', async () => {
    for (const path of ['/sources', '/search?q=x', '/block-rules', `/sources/${crypto.randomUUID()}`]) {
      expect((await api('GET', path)).status).toBe(401);
    }
  });
});

describe('source reads', () => {
  it('lists newest first with an HMAC cursor bound to the filters', async () => {
    const a = await ingest(mail('First', 'one'), { now: T0 });
    const b = await ingest(mail('Second', 'two'), { now: T0 + 1000 });
    const page1 = await api('GET', '/sources?limit=1', { token });
    expect(page1.body.data.sources.map((s: { id: string }) => s.id)).toEqual([b]);
    expect(page1.body.data.sources[0]).toMatchObject({ subject: 'Second', admission: 'normal', state: 'ready', from: ['ops@example.com'] });
    const cursor = page1.body.data.nextCursor as string;
    const page2 = await api('GET', `/sources?limit=1&cursor=${encodeURIComponent(cursor)}`, { token });
    expect(page2.body.data.sources.map((s: { id: string }) => s.id)).toEqual([a]);
    expect(page2.body.data.nextCursor).toBeNull();

    const tampered = `${cursor.slice(0, -2)}AA`;
    expect((await api('GET', `/sources?limit=1&cursor=${encodeURIComponent(tampered)}`, { token })).status).toBe(400);
    expect((await api('GET', `/sources?admission=quarantine&cursor=${encodeURIComponent(cursor)}`, { token })).status).toBe(400);
    const later = T0 + CURSOR_TTL_MS;
    const refreshed = await api('POST', '/auth/refresh', { auth: `Refresh ${owner.refreshToken}`, now: later });
    expect((await api('GET', `/sources?limit=1&cursor=${encodeURIComponent(cursor)}`, { token: refreshed.body.data.accessToken, now: later })).body.error?.code).toBe('cursor_expired');
  });

  it('rejects unknown query parameters and out-of-range limits', async () => {
    expect((await api('GET', '/sources?raw=1', { token })).status).toBe(400);
    expect((await api('GET', '/sources?limit=201', { token })).status).toBe(400);
  });

  it('returns full text, attachments and provenance, and hides deleted sources', async () => {
    const id = await ingest(fixture('attachments-no-message-id'));
    const detail = await api('GET', `/sources/${id}`, { token });
    expect(detail.body.data.text).toBe('Report attached. Chart:\n\n[usage chart]');
    expect(detail.body.data.attachments.map((a: { name: string; extraction: string }) => [a.name, a.extraction])).toEqual([
      ['chart.png', 'stored'],
      ['report.pdf', 'stored'],
    ]);
    expect(detail.body.data.provenance.admission.detector).toBe('spam-rules-v1');

    const file = detail.body.data.attachments[1];
    const content = await api('GET', `/attachments/${file.id}/content`, { token });
    expect(content.status).toBe(200);
    expect(content.headers.get('content-disposition')).toMatch(/^attachment; filename="report.pdf"/);
    expect(content.headers.get('x-content-type-options')).toBe('nosniff');
    expect(content.headers.get('content-type')).toBe('application/octet-stream');
    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', content.body.data))].map((b) => b.toString(16).padStart(2, '0')).join('');
    expect(digest).toBe(file.sha256);

    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleted' WHERE id = ?").bind(id).run();
    expect((await api('GET', `/sources/${id}`, { token })).status).toBe(404);
    expect((await api('GET', `/attachments/${file.id}/content`, { token })).status).toBe(404);
  });

  it('pages chunks with stable citation IDs', async () => {
    const paragraph = 'Chunk paging body text. '.repeat(200);
    const id = await ingest(mail('Long', Array.from({ length: 4 }, () => paragraph).join('\n\n')));
    const first = await api('GET', `/sources/${id}/chunks?limit=1`, { token });
    expect(first.body.data.chunks[0].chunkId).toBe(`${id}:body-v1:0`);
    const second = await api('GET', `/sources/${id}/chunks?limit=1&cursor=${encodeURIComponent(first.body.data.nextCursor)}`, { token });
    expect(second.body.data.chunks[0].chunkId).toBe(`${id}:body-v1:1`);
  });
});

describe('exact search', () => {
  it('finds sources by text and keeps quarantine out of the archive view', async () => {
    const normal = await ingest(mail('Hosting invoice', 'Invoice INV-77 is due.'));
    const quarantined = await ingest(spam);
    const archive = await api('GET', '/search?q=invoice', { token });
    expect(archive.body.data.results.map((r: { sourceId: string }) => r.sourceId)).toEqual([normal]);
    expect(archive.body.data.results[0].snippet).toContain('[Invoice]');
    const quarantine = await api('GET', '/search?q=invoice&view=quarantine', { token });
    expect(quarantine.body.data.results.map((r: { sourceId: string }) => r.sourceId)).toEqual([quarantined]);
  });

  it('treats FTS syntax as literal text', async () => {
    await ingest(mail('Plain', 'nothing special'));
    for (const q of ['subject:plain', 'nothing OR *', '"unbalanced', 'NEAR(a b)', "x'); DROP TABLE sources; --"]) {
      const result = await api('GET', `/search?q=${encodeURIComponent(q)}`, { token });
      expect(result.status, q).toBe(200);
    }
    expect((await api('GET', `/search?q=${encodeURIComponent('nothing special')}`, { token })).body.data.results).toHaveLength(1);
  });

  it('filters by sender and date and excludes deleted sources', async () => {
    const early = await ingest(mail('Alpha report', 'quarterly numbers', 'cfo@example.com'), { now: T0 });
    const late = await ingest(mail('Beta report', 'quarterly numbers', 'ops@example.com'), { now: T0 + 86_400_000 });
    const bySender = await api('GET', '/search?q=quarterly&sender=CFO@example.com', { token });
    expect(bySender.body.data.results.map((r: { sourceId: string }) => r.sourceId)).toEqual([early]);
    const byDate = await api('GET', `/search?q=quarterly&from=${new Date(T0 + 1000).toISOString()}`, { token });
    expect(byDate.body.data.results.map((r: { sourceId: string }) => r.sourceId)).toEqual([late]);
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleting' WHERE id = ?").bind(late).run();
    expect((await api('GET', '/search?q=quarterly', { token })).body.data.results.map((r: { sourceId: string }) => r.sourceId)).toEqual([early]);
  });

  it('bounds queries and keeps semantic mode disabled in P1', async () => {
    expect((await api('GET', `/search?q=${'a'.repeat(201)}`, { token })).status).toBe(400);
    expect((await api('GET', `/search?q=${Array.from({ length: 21 }, (_, i) => `w${i}`).join('+')}`, { token })).status).toBe(400);
    expect((await api('GET', '/search?q=x&mode=semantic', { token })).body.error?.code).toBe('feature_disabled');
  });

  it('pages results with a continuation cursor', async () => {
    for (let i = 0; i < 3; i += 1) await ingest(mail(`Status ${i}`, `heartbeat ${i}`), { now: T0 + i });
    const first = await api('GET', '/search?q=heartbeat&limit=2', { token });
    expect(first.body.data.results).toHaveLength(2);
    const next = await api('GET', `/search?q=heartbeat&limit=2&cursor=${encodeURIComponent(first.body.data.nextCursor)}`, { token });
    expect(next.body.data.results).toHaveLength(1);
    const ids = [...first.body.data.results, ...next.body.data.results].map((r: { sourceId: string }) => r.sourceId);
    expect(new Set(ids).size).toBe(3);
  });
});

describe('block rules and quarantine controls', () => {
  it('creates a block rule idempotently and shows its exact effect', async () => {
    const key = crypto.randomUUID();
    const body = { kind: 'domain', matchField: 'either', value: 'Spam.Example' };
    const created = await api('POST', '/block-rules', { token, key, body });
    expect(created.status).toBe(200);
    expect(created.body.data).toMatchObject({ value: 'spam.example', enabled: true, revision: 1 });
    expect(created.body.data.effect).toContain('exactly the domain spam.example (not its subdomains)');
    const replay = await api('POST', '/block-rules', { token, key, body });
    expect(replay.body.data).toEqual(created.body.data);
    expect((await api('POST', '/block-rules', { token, key, body: { ...body, value: 'other.example' } })).body.error?.code).toBe('idempotency_conflict');
    expect((await api('POST', '/block-rules', { token, body })).status).toBe(400);
    expect((await api('POST', '/block-rules', { token, key: crypto.randomUUID(), body })).body.error?.code).toBe('duplicate_rule');
    expect((await api('POST', '/block-rules', { token, key: crypto.randomUUID(), body: { ...body, value: '*.spam.example' } })).status).toBe(400);
  });

  it('updates rules only at the expected revision', async () => {
    const created = await api('POST', '/block-rules', { token, key: crypto.randomUUID(), body: { kind: 'address', matchField: 'envelope_from', value: 'promo@spam.example' } });
    const id = created.body.data.id;
    const disabled = await api('PATCH', `/block-rules/${id}`, { token, key: crypto.randomUUID(), body: { expectedRevision: 1, enabled: false } });
    expect(disabled.body.data).toMatchObject({ enabled: false, revision: 2 });
    const stale = await api('PATCH', `/block-rules/${id}`, { token, key: crypto.randomUUID(), body: { expectedRevision: 1, enabled: true } });
    expect(stale).toMatchObject({ status: 409, body: { error: { code: 'stale_revision', details: { currentRevision: 2 } } } });
  });

  it('quarantines and releases a source by revision and records each change', async () => {
    const id = await ingest(mail('Newsletter', 'weekly digest'));
    const quarantined = await api('POST', `/sources/${id}/quarantine`, { token, key: crypto.randomUUID(), body: { expectedRevision: 2, reason: 'looks like spam' } });
    expect(quarantined.body.data).toMatchObject({ admission: 'quarantine', revision: 3 });
    expect((await api('GET', '/search?q=digest', { token })).body.data.results).toHaveLength(0);
    expect((await api('POST', `/sources/${id}/release`, { token, key: crypto.randomUUID(), body: { expectedRevision: 2 } })).body.error?.code).toBe('stale_revision');
    const released = await api('POST', `/sources/${id}/release`, { token, key: crypto.randomUUID(), body: { expectedRevision: 3 } });
    expect(released.body.data).toMatchObject({ admission: 'normal', revision: 4 });
    const changes = await env.CONTROL_DB.prepare('SELECT revision FROM changes WHERE entity_id = ? ORDER BY seq').bind(id).all<{ revision: number }>();
    expect(changes.results.map((c) => c.revision)).toEqual([2, 3, 4]);
  });

  it('manages spam overrides without releasing existing quarantine', async () => {
    const id = await ingest(spam);
    const created = await api('POST', '/spam-overrides', { token, key: crypto.randomUUID(), body: { kind: 'domain', value: 'x.example', verdict: 'trust' } });
    expect(created.body.data).toMatchObject({ value: 'x.example', verdict: 'trust', enabled: true });
    expect((await api('GET', `/sources/${id}`, { token })).body.data.source.admission).toBe('quarantine');
    const list = await api('GET', '/spam-overrides', { token });
    expect(list.body.data.overrides).toHaveLength(1);
  });
});
