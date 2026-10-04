import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { newSession } from '../../src/auth/sessions';
import { type LedgerHead, appendLedger, recordKey, verifyHead } from '../../src/deletion/ledger';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const MIN = 60_000;
let owner: Awaited<ReturnType<typeof enrollOwner>>;
let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-a000-${String((keyCount += 1)).padStart(12, '0')}`;

const mail = (o: { subject?: string; body?: string; id?: string; inReplyTo?: string; attachment?: string }) =>
  [
    'From: ops@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${o.subject ?? 'Private invoice 4471'}`,
    ...(o.id ? [`Message-ID: ${o.id}`] : []),
    ...(o.inReplyTo ? [`In-Reply-To: ${o.inReplyTo}`] : []),
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain',
    '',
    o.body ?? 'Account number 99-1234 is overdue.',
    '--b1',
    'Content-Type: text/plain; name="note.txt"',
    'Content-Disposition: attachment; filename="note.txt"',
    '',
    o.attachment ?? 'attached secret words',
    '--b1--',
    '',
  ].join('\r\n');

async function triaged(raw: string, now = T0): Promise<{ sourceId: string; concernId: string }> {
  const sourceId = await ingest(raw, { now });
  await runPending('triage', now);
  await runPending('extract', now);
  const row = await env.CONTROL_DB.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(sourceId).first<{ concern_id: string }>();
  return { sourceId, concernId: row!.concern_id };
}

async function review(sourceIds: string[], now = T0) {
  const created = await api('POST', '/reviews', { token, key: key(), now, body: { effect: 'archive.delete', params: { sourceIds } } });
  expect(created.status).toBe(200);
  return created.body.data;
}
const confirm = (reviewId: string, paramsDigest: string, options: { now?: number; key?: string; env?: Env } = {}) =>
  api('POST', `/reviews/${reviewId}/confirm`, { token, key: options.key ?? key(), now: options.now ?? T0, body: { paramsDigest }, ...(options.env ? { env: options.env } : {}) });

async function r2Keys(prefix = ''): Promise<string[]> {
  return (await env.ARCHIVE_BUCKET.list({ prefix })).objects.map((o) => o.key);
}

beforeEach(async () => {
  owner = await enrollOwner();
  token = owner.token;
});

describe('archive deletion review', () => {
  it('previews exact targets, then hides the source and its derivatives immediately', async () => {
    const victim = await triaged(mail({}));
    const keep = await triaged(mail({ subject: 'Unrelated renewal', body: 'Keep me.', attachment: 'kept words' }), T0 + 1000);
    const attachmentId = (await api('GET', `/sources/${victim.sourceId}`, { token })).body.data.attachments[0].id;

    const planned = await review([victim.sourceId]);
    expect(planned).toMatchObject({ effect: 'archive.delete', requiresRecentAuth: true, expiresAt: new Date(T0 + 10 * MIN).toISOString() });
    expect(planned.preview).toMatchObject({
      sources: [{ id: victim.sourceId, subject: 'Private invoice 4471' }],
      attachments: 1,
      concernsErased: [victim.concernId],
      concernsDetached: [],
      expectedCount: 1,
    });

    expect((await confirm(planned.reviewId, '0'.repeat(64))).status).toBe(409);
    const done = await confirm(planned.reviewId, planned.paramsDigest);
    expect(done.status).toBe(200);
    expect(done.body.data).toMatchObject({ state: 'succeeded', receipt: { ledgerSeq: 1, sourcesDeleted: 1 } });

    expect((await api('GET', `/sources/${victim.sourceId}`, { token })).status).toBe(404);
    expect((await api('GET', `/attachments/${attachmentId}/content`, { token })).status).toBe(404);
    expect((await api('GET', `/concerns/${victim.concernId}`, { token })).status).toBe(404);
    expect((await api('GET', '/search?q=overdue', { token })).body.data.results).toEqual([]);
    expect((await api('GET', '/search?q=secret', { token })).body.data.results).toEqual([]);
    expect((await api('GET', '/search?q=kept', { token })).body.data.results).toHaveLength(1);

    const tombstones = await env.CONTROL_DB.prepare('SELECT entity_type, deleted FROM changes WHERE entity_id IN (?, ?) ORDER BY seq DESC LIMIT 2').bind(victim.sourceId, victim.concernId).all();
    expect(tombstones.results).toEqual(expect.arrayContaining([{ entity_type: 'source', deleted: 1 }, { entity_type: 'concern', deleted: 1 }]));
    const history = await env.CONTROL_DB.prepare("SELECT projection_json FROM changes WHERE entity_type = 'concern' AND entity_id = ?").bind(victim.concernId).all<{ projection_json: string }>();
    expect(history.results.every((r) => !r.projection_json.includes('4471'))).toBe(true);

    const operation = await api('GET', `/operations/${done.body.data.operationId}`, { token });
    expect(operation.body.data).toMatchObject({ state: 'succeeded', intent: { effect: 'archive.delete', params: { sourceIds: [victim.sourceId] } } });
    expect((await api('GET', `/sources/${keep.sourceId}`, { token })).status).toBe(200);
  });

  it('writes a content-free signed ledger record before the control change', async () => {
    const victim = await triaged(mail({}));
    const planned = await review([victim.sourceId]);
    await confirm(planned.reviewId, planned.paramsDigest);
    const head = (await (await env.DELETION_LEDGER.get('head.json'))!.json()) as LedgerHead;
    expect(head.seq).toBe(1);
    expect(await verifyHead(env, head)).toBe(true);
    const record = await (await env.DELETION_LEDGER.get(recordKey(1)))!.text();
    expect(JSON.parse(record)).toMatchObject({ version: 1, seq: 1, sourceIds: [victim.sourceId], prevHash: null });
    expect(record).not.toContain('invoice');
    expect(record).not.toContain('vendor.example');
  });

  it('cleans archive rows, R2 objects and derived rows, leaving other sources intact', async () => {
    const victim = await triaged(mail({}));
    const keep = await triaged(mail({ subject: 'Unrelated', body: 'Keep me.', attachment: 'kept words' }), T0 + 1000);
    expect((await r2Keys(`attachments/${victim.sourceId}/`)).length).toBe(1);
    const planned = await review([victim.sourceId]);
    await confirm(planned.reviewId, planned.paramsDigest);
    expect(await runPending('cleanup')).toEqual(['complete']);

    for (const prefix of [`staging/${victim.sourceId}/`, `attachments/${victim.sourceId}/`]) expect(await r2Keys(prefix)).toEqual([]);
    expect((await r2Keys(`attachments/${keep.sourceId}/`)).length).toBe(1);
    const archive = await env.ARCHIVE_0001.prepare(
      'SELECT (SELECT COUNT(*) FROM sources WHERE id = ?1) AS s, (SELECT COUNT(*) FROM chunks WHERE source_id = ?1) AS c, (SELECT COUNT(*) FROM source_fts WHERE source_id = ?1) AS f, (SELECT COUNT(*) FROM attachments WHERE source_id = ?1) AS a',
    )
      .bind(victim.sourceId)
      .first();
    expect(archive).toEqual({ s: 0, c: 0, f: 0, a: 0 });
    expect(await env.CONTROL_DB.prepare('SELECT state, staged_key FROM source_registry WHERE id = ?').bind(victim.sourceId).first()).toEqual({ state: 'deleted', staged_key: null });
    expect(await env.CONTROL_DB.prepare('SELECT state FROM deletion_ledger WHERE source_id = ?').bind(victim.sourceId).first()).toEqual({ state: 'completed' });
    const derived = await env.CONTROL_DB.prepare(
      'SELECT (SELECT COUNT(*) FROM attachment_processing WHERE source_id = ?1) AS ap, (SELECT COUNT(*) FROM triage_results WHERE source_id = ?1) AS tr, (SELECT COUNT(*) FROM source_threads WHERE source_id = ?1) AS th',
    )
      .bind(victim.sourceId)
      .first();
    expect(derived).toEqual({ ap: 0, tr: 0, th: 0 });
    expect((await api('GET', `/sources/${keep.sourceId}/chunks`, { token })).body.data.chunks.length).toBeGreaterThan(0);
  });

  it('detaches deleted evidence from a concern that keeps other sources and scrubs its old title', async () => {
    const first = await triaged(mail({ subject: 'Private invoice 4471', id: '<a@vendor.example>' }));
    const reply = await triaged(mail({ subject: 'Payment plan', id: '<b@vendor.example>', inReplyTo: '<a@vendor.example>', body: 'Plan agreed.' }), T0 + MIN);
    expect(reply.concernId).toBe(first.concernId);
    const planned = await review([first.sourceId]);
    expect(planned.preview).toMatchObject({ concernsErased: [], concernsDetached: [first.concernId] });
    await confirm(planned.reviewId, planned.paramsDigest);
    const view = (await api('GET', `/concerns/${first.concernId}`, { token })).body.data;
    expect(view.concern).toMatchObject({ title: 'Payment plan', sourceIds: [reply.sourceId] });
    expect(view.events[0]).toMatchObject({ kind: 'evidence_deleted' });
    const stored = await env.CONTROL_DB.prepare('SELECT before_json || after_json AS j FROM concern_events WHERE concern_id = ?').bind(first.concernId).all<{ j: string }>();
    expect(stored.results.some((r) => r.j.includes('4471'))).toBe(false);
  });

  it('requires recent authentication, current revisions and an unexpired review', async () => {
    const victim = await triaged(mail({}));
    const planned = await review([victim.sourceId]);
    expect((await confirm(planned.reviewId, planned.paramsDigest, { now: T0 + 6 * MIN })).status).toBe(403);

    await api('POST', `/sources/${victim.sourceId}/quarantine`, { token, key: key(), body: { expectedRevision: 2 } });
    const stale = await confirm(planned.reviewId, planned.paramsDigest);
    expect(stale.status).toBe(409);
    expect(stale.body.error?.code).toBe('stale_review');

    const again = await review([victim.sourceId]);
    await env.CONTROL_DB.prepare('UPDATE action_reviews SET expires_at = ? WHERE id = ?').bind(T0, again.reviewId).run();
    expect((await confirm(again.reviewId, again.paramsDigest)).status).toBe(410);
    expect((await api('GET', `/sources/${victim.sourceId}`, { token })).status).toBe(200);
  });

  it('leaves the deletion pending when the ledger cannot be written', async () => {
    const victim = await triaged(mail({}));
    const planned = await review([victim.sourceId]);
    const broken = { ...env, DELETION_LEDGER: { get: async () => null, put: async () => { throw new Error('r2 down'); } } as unknown as R2Bucket };
    const failed = await confirm(planned.reviewId, planned.paramsDigest, { env: broken });
    expect(failed.status).toBe(503);
    expect(failed.body.error).toMatchObject({ code: 'ledger_unavailable' });
    expect((await api('GET', `/sources/${victim.sourceId}`, { token })).status).toBe(200);
    expect((await confirm(planned.reviewId, planned.paramsDigest)).status).toBe(200);
  });

  it('claims a review once and replays the same confirmation', async () => {
    const victim = await triaged(mail({}));
    const planned = await review([victim.sourceId]);
    const k = key();
    const first = await confirm(planned.reviewId, planned.paramsDigest, { key: k });
    const replay = await confirm(planned.reviewId, planned.paramsDigest, { key: k });
    expect(replay.body.data).toEqual(first.body.data);
    const second = await confirm(planned.reviewId, planned.paramsDigest);
    expect(second.status).toBe(409);
    expect(second.body.error?.code).toBe('review_consumed');
  });

  it('turns retained mutation results that quote a deleted source into a deletion notice', async () => {
    const victim = await triaged(mail({}));
    const k = key();
    const body = { expectedRevision: 1, kind: 'acknowledge' };
    expect((await api('POST', `/concerns/${victim.concernId}/actions`, { token, key: k, body })).status).toBe(200);
    const planned = await review([victim.sourceId]);
    await confirm(planned.reviewId, planned.paramsDigest);
    await runPending('cleanup');
    const replay = await api('POST', `/concerns/${victim.concernId}/actions`, { token, key: k, body });
    expect(replay.status).toBe(410);
    expect(replay.body.error?.code).toBe('deleted');
  });
});

describe('ledger chain', () => {
  it('skips an orphaned record and keeps the chain linked to the signed head', async () => {
    const first = await appendLedger(env, { operationId: 'op-1', sourceIds: ['s1'], deletedAt: T0 });
    // A writer that crashed after its record but before advancing the head.
    await env.DELETION_LEDGER.put(recordKey(2), '{"orphan":true}');
    const next = await appendLedger(env, { operationId: 'op-2', sourceIds: ['s2'], deletedAt: T0 });
    expect(next.seq).toBe(3);
    expect(JSON.parse(await (await env.DELETION_LEDGER.get(recordKey(3)))!.text())).toMatchObject({ seq: 3, prevHash: first.recordHash });
    expect(await verifyHead(env, next)).toBe(true);
  });

  it('refuses a head whose signature does not verify', async () => {
    await env.DELETION_LEDGER.put('head.json', JSON.stringify({ seq: 7, recordHash: 'x', signature: 'forged' }));
    await expect(appendLedger(env, { operationId: 'op', sourceIds: ['s'], deletedAt: T0 })).rejects.toThrow('ledger_head_invalid');
  });
});

describe('device revocation review', () => {
  it('revokes another device and its sessions after recent authentication', async () => {
    const deviceId = crypto.randomUUID();
    await env.CONTROL_DB.prepare("INSERT INTO devices(id, owner_id, name, capabilities_json) VALUES (?, 'owner', 'Old tablet', '{}')").bind(deviceId).run();
    const session = await newSession(env.CONTROL_DB, { deviceId, authEpoch: 'current', recentAuthAt: null, now: T0 });
    await session.statement.run();
    const other = session.tokens.accessToken;
    expect((await api('GET', '/devices', { token: other })).status).toBe(200);

    const planned = await api('POST', '/reviews', { token, key: key(), body: { effect: 'device.revoke', params: { deviceId } } });
    expect(planned.body.data.preview).toMatchObject({ device: { id: deviceId, name: 'Old tablet' }, current: false, sessionsRevoked: 1, expectedCount: 1 });
    expect((await confirm(planned.body.data.reviewId, planned.body.data.paramsDigest)).status).toBe(200);
    expect((await api('GET', '/devices', { token: other })).status).toBe(401);
    const devices = (await api('GET', '/devices', { token })).body.data.devices as { id: string; revoked: boolean }[];
    expect(devices.find((d) => d.id === deviceId)?.revoked).toBe(true);
    expect((await api('POST', '/reviews', { token, key: key(), body: { effect: 'device.revoke', params: { deviceId } } })).status).toBe(404);
  });

  it('rejects unknown effects and malformed parameters', async () => {
    expect((await api('POST', '/reviews', { token, key: key(), body: { effect: 'cloudflare.dns.delete', params: {} } })).status).toBe(400);
    expect((await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [] } } })).status).toBe(400);
    expect((await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [crypto.randomUUID()] } } })).status).toBe(404);
  });
});
