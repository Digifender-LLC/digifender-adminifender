import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { CHANGE_RETENTION_MS, pruneChanges } from '../../src/sync/retention';
import { SNAPSHOT_TTL_MS } from '../../src/sync/routes';
import fixture from '../../../contracts/fixtures/v1/sync-changes/valid/concern-source-tombstone.json';
import { T0, api, enrollOwner } from './support';

const DAY = 24 * 60 * 60 * 1000;
let token: string;

async function change(entityId: string, revision: number, createdAt: number, deleted = false): Promise<number> {
  const row = await env.CONTROL_DB.prepare('INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING seq')
    .bind('concern', entityId, revision, deleted ? 1 : 0, deleted ? '{}' : JSON.stringify({ id: entityId, revision }), createdAt)
    .first<{ seq: number }>();
  return row!.seq;
}

const seqs = async () => (await env.CONTROL_DB.prepare('SELECT entity_id, revision FROM changes ORDER BY seq').all<{ entity_id: string; revision: number }>()).results.map((r) => `${r.entity_id}@${r.revision}`);

beforeEach(async () => {
  token = (await enrollOwner()).token;
  // Enrollment writes no changes; the feed below is fully synthetic.
  await env.CONTROL_DB.prepare('DELETE FROM changes').run();
});

describe('bootstrap', () => {
  it('pages the latest live projections as of a fixed sequence', async () => {
    await change('a', 1, T0);
    await change('b', 1, T0);
    await change('a', 2, T0);
    await change('c', 1, T0);
    await change('c', 2, T0, true);
    const first = await api('GET', '/sync/bootstrap?limit=1', { token });
    expect(first.body.data.entities).toEqual([{ entityType: 'concern', entityId: 'b', revision: 1, projection: { id: 'b', revision: 1 } }]);
    // A change after the snapshot does not leak into later pages.
    await change('a', 3, T0);
    const second = await api('GET', `/sync/bootstrap?limit=5&cursor=${encodeURIComponent(first.body.data.nextCursor)}`, { token });
    expect(second.body.data.entities.map((e: { entityId: string; revision: number }) => `${e.entityId}@${e.revision}`)).toEqual(['a@2']);
    expect(second.body.data.nextCursor).toBeNull();
    // Continuing from the snapshot sequence delivers what happened since.
    const since = await api('GET', `/sync/changes?after=${first.body.data.snapshotSeq}`, { token });
    expect(since.body.data.changes.map((c: { entityId: string; revision: number }) => `${c.entityId}@${c.revision}`)).toEqual(['a@3']);
  });

  it('expires a snapshot after 24 hours', async () => {
    await change('a', 1, T0);
    await change('b', 1, T0);
    const first = await api('GET', '/sync/bootstrap?limit=1', { token });
    expect(await env.CONTROL_DB.prepare('SELECT expires_at - created_at AS ttl FROM sync_snapshots').first()).toEqual({ ttl: SNAPSHOT_TTL_MS });
    await env.CONTROL_DB.prepare('UPDATE sync_snapshots SET expires_at = ?').bind(T0 + 1000).run();
    const cursor = encodeURIComponent(first.body.data.nextCursor);
    expect((await api('GET', `/sync/bootstrap?limit=1&cursor=${cursor}`, { token, now: T0 + 999 })).status).toBe(200);
    expect((await api('GET', `/sync/bootstrap?limit=1&cursor=${cursor}`, { token, now: T0 + 1000 })).status).toBe(410);
  });
});

describe('changes feed', () => {
  it('returns ordered projections and tombstones with throughSeq and hasMore', async () => {
    const s1 = await change('a', 1, T0);
    await change('a', 2, T0, true);
    const page = await api('GET', `/sync/changes?after=${s1 - 1}&limit=1`, { token });
    expect(page.body.data).toMatchObject({ throughSeq: s1, hasMore: true });
    const next = await api('GET', `/sync/changes?after=${s1}`, { token });
    expect(next.body.data.changes).toEqual([{ seq: s1 + 1, entityType: 'concern', entityId: 'a', revision: 2, deleted: true, projection: null }]);
    expect(next.body.data.hasMore).toBe(false);
    const empty = await api('GET', `/sync/changes?after=${s1 + 1}`, { token });
    expect(empty.body.data).toEqual({ changes: [], throughSeq: s1 + 1, hasMore: false });
    expect((await api('GET', '/sync/changes', { token })).status).toBe(400);
    expect((await api('GET', '/sync/changes?after=0')).status).toBe(401);
  });
});

describe('retention', () => {
  const old = T0 - CHANGE_RETENTION_MS - DAY;

  it('keeps the latest projection of every live entity however old', async () => {
    await change('a', 1, old);
    await change('a', 2, old);
    await change('b', 1, old);
    expect(await pruneChanges(env.CONTROL_DB, T0)).toEqual({ superseded: 1, tombstones: 0 });
    expect(await seqs()).toEqual(['a@2', 'b@1']);
  });

  it('drops old tombstones, raises the cursor floor and makes older cursors rebootstrap', async () => {
    const s1 = await change('a', 1, old);
    const s2 = await change('a', 2, old, true);
    await change('b', 1, T0);
    expect(await pruneChanges(env.CONTROL_DB, T0)).toEqual({ superseded: 1, tombstones: 1 });
    expect(await seqs()).toEqual(['b@1']);
    const expired = await api('GET', `/sync/changes?after=${s1}`, { token });
    expect(expired.status).toBe(410);
    expect(expired.body.error).toMatchObject({ code: 'cursor_expired', details: { floorSeq: s2 } });
    expect((await api('GET', `/sync/changes?after=${s2}`, { token })).status).toBe(200);
  });

  it('keeps versions a live snapshot still pages', async () => {
    await change('a', 1, old);
    await api('GET', '/sync/bootstrap', { token });
    await change('a', 2, old);
    expect(await pruneChanges(env.CONTROL_DB, T0)).toEqual({ superseded: 0, tombstones: 0 });
    expect(await seqs()).toEqual(['a@1', 'a@2']);
    await env.CONTROL_DB.prepare('UPDATE sync_snapshots SET expires_at = ?').bind(T0).run();
    expect(await pruneChanges(env.CONTROL_DB, T0)).toEqual({ superseded: 1, tombstones: 0 });
    expect(await seqs()).toEqual(['a@2']);
  });
});

describe('source projections', () => {
  it('publishes the same list fields a later source change carries', async () => {
    const { ingest } = await import('./support');
    const id = await ingest('From: Billing <billing@vendor.example>\r\nTo: inbox@adminifender.example\r\nSubject: Invoice 42\r\n\r\nAmount due.\r\n');
    const feed = await api('GET', '/sync/changes?after=0', { token });
    const published = feed.body.data.changes.find((c: { entityType: string; entityId: string }) => c.entityType === 'source' && c.entityId === id);
    expect(published.projection).toEqual({
      id,
      kind: 'email',
      state: 'ready',
      admission: 'normal',
      privacy: 'hybrid',
      revision: 2,
      receivedAt: new Date(T0).toISOString(),
      subject: 'Invoice 42',
      from: ['billing@vendor.example'],
      to: ['inbox@adminifender.example'],
    });
    const quarantined = await api('POST', `/sources/${id}/quarantine`, { token, key: '00000000-0000-4000-8000-00000000a001', body: { expectedRevision: 2 } });
    expect(quarantined.status).toBe(200);
    const later = (await api('GET', `/sync/changes?after=${feed.body.data.throughSeq}`, { token })).body.data.changes.find((c: { entityType: string }) => c.entityType === 'source');
    expect(Object.keys(later.projection).sort()).toEqual(Object.keys(published.projection).sort());
  });

  it('matches the shared sync-changes fixture the phone parses', async () => {
    const { ingest, runPending } = await import('./support');
    await ingest('From: billing@vendor.example\r\nTo: inbox@adminifender.example\r\nSubject: Invoice 42\r\n\r\nAmount due.\r\n');
    expect(await runPending('triage')).toEqual(['complete']);
    const page = (await api('GET', '/sync/changes?after=0', { token })).body.data;
    const keysOf = (type: string, changes: { entityType: string; projection: object | null }[]) => Object.keys(changes.find((c) => c.entityType === type)!.projection!).sort();
    expect(Object.keys(page).sort()).toEqual(Object.keys(fixture).sort());
    expect(Object.keys(page.changes[0]).sort()).toEqual(Object.keys(fixture.changes[0]!).sort());
    for (const type of ['source', 'concern']) expect(keysOf(type, page.changes)).toEqual(keysOf(type, fixture.changes));
  });
});
