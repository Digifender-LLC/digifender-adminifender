import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { currentBriefing, reviewBriefing, SECTION_LIMIT, SNAPSHOT_LIMIT, SNAPSHOT_TTL_MS } from '../../src/briefings/routes';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, quietEnv } from './support';

let token: string;
beforeEach(async () => { token = (await enrollOwner()).token; });

const ids = (rows: { id: string }[]) => rows.map((r) => r.id);
const get = async (now = T0) => {
  const response = await api('GET', '/briefings/current', { token, now });
  expect(response.status).toBe(200);
  return response.body.data;
};
const review = (id: string, now = T0) => api('POST', `/briefings/${id}/review`, { token, key: crypto.randomUUID(), body: {}, now });

/** Direct synthetic rows keep backlog fixtures small while exercising the real authenticated routes and D1. */
async function seedConcerns(n: number, kind = 'created') {
  const result: string[] = [];
  for (let i = 0; i < n; i++) {
    const id = crypto.randomUUID();
    result.push(id);
    await env.CONTROL_DB.batch([
      env.CONTROL_DB.prepare("INSERT INTO concerns(id, revision, title, status, importance, updated_at) VALUES (?, 1, 'Synthetic notice', 'open', 'important', ?)").bind(id, T0 + i),
      env.CONTROL_DB.prepare("INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) VALUES (?, ?, 0, 1, ?, 'null', '{}', ?)").bind(crypto.randomUUID(), id, kind, T0 + i),
      env.CONTROL_DB.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('concern', ?, 1, 0, '{}', ?)").bind(id, T0 + i),
    ]);
  }
  return result;
}

async function seedSources(n: number) {
  const result: string[] = [];
  for (let i = 0; i < n; i++) {
    const id = crypto.randomUUID();
    result.push(id);
    await env.CONTROL_DB.batch([
      env.CONTROL_DB.prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('source', ?, 1, 0, '{}', ?)").bind(id, T0 + i),
      env.CONTROL_DB.prepare("INSERT INTO source_registry(id, kind, external_key, content_hash, admission, privacy, state, revision, received_at, ready_seq) VALUES (?, 'email', ?, 'synthetic-hash', 'normal', 'hybrid', 'ready', 1, ?, (SELECT MAX(seq) FROM changes))").bind(id, id, T0 + i),
    ]);
  }
  return result;
}

/** Injects a competing committed write just before the snapshot's assertion transaction. */
function beforeNextBatch(write: () => Promise<unknown>): Env {
  const db = env.CONTROL_DB;
  let first = true;
  const wrapped = new Proxy(db, {
    get(target, property) {
      if (property === 'batch') return async (statements: D1PreparedStatement[]) => {
        if (first) { first = false; await write(); }
        return target.batch(statements);
      };
      const value = Reflect.get(target, property);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
  return { ...quietEnv(), CONTROL_DB: wrapped };
}

describe('briefing display boundaries', () => {
  it('never reviews the 101st new concern until it is displayed', async () => {
    const expected = await seedConcerns(SECTION_LIMIT + 1);
    const first = await get();
    expect(ids(first.newConcerns)).toEqual(expected.slice(0, SECTION_LIMIT));
    expect(first.hasMore).toBe(true);
    expect((await review(first.id)).status).toBe(200);
    const second = await get(T0 + 1);
    expect(ids(second.newConcerns)).toEqual(expected.slice(SECTION_LIMIT));
    expect(second.hasMore).toBe(false);
    expect(second.snapshotSeq).toBeGreaterThan(first.snapshotSeq);
  });

  it('independently bounds changed concerns and new sources without losing either backlog', async () => {
    const sources = await seedSources(SECTION_LIMIT + 1);
    const changed = await seedConcerns(SECTION_LIMIT + 1, 'evidence_added');
    const first = await get();
    expect(first.newSourceIds).toEqual(sources.slice(0, SECTION_LIMIT));
    expect(first.changedConcerns).toEqual([]);
    await review(first.id);
    const second = await get(T0 + 1);
    expect(second.newSourceIds).toEqual(sources.slice(SECTION_LIMIT));
    expect(ids(second.changedConcerns)).toEqual(changed.slice(0, SECTION_LIMIT));
    expect(second.hasMore).toBe(true);
    await review(second.id, T0 + 2);
    const third = await get(T0 + 3);
    expect(ids(third.changedConcerns)).toEqual(changed.slice(SECTION_LIMIT));
    expect(third.newSourceIds).toEqual([]);
  });

  it('excludes deleted groups from the bound so they cannot prevent progress', async () => {
    const removed = await seedConcerns(SECTION_LIMIT + 1);
    for (const id of removed) await env.CONTROL_DB.batch([
      env.CONTROL_DB.prepare('DELETE FROM concern_events WHERE concern_id = ?').bind(id),
      env.CONTROL_DB.prepare('DELETE FROM concerns WHERE id = ?').bind(id),
    ]);
    const live = await seedConcerns(1);
    const first = await get();
    expect(ids(first.newConcerns)).toEqual(live);
    expect(first.hasMore).toBe(false);
  });

  it('refuses a mixed snapshot if a write lands between reads and its receipt', async () => {
    await seedConcerns(1);
    const racing = beforeNextBatch(() => seedConcerns(1));
    const request = new Request('https://adminifender.example/api/v1/briefings/current', { headers: { authorization: `Bearer ${token}` } });
    await expect(currentBriefing(request, racing, T0)).rejects.toMatchObject({ status: 409, code: 'snapshot_changed' });
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM briefings').first()).toEqual({ n: 0 });
    expect((await get()).newConcerns).toHaveLength(2);
  });

  it('refuses a snapshot if another review advances the cursor while it loads', async () => {
    await seedConcerns(1);
    const first = await get();
    const racing = beforeNextBatch(() => reviewBriefing(quietEnv(), T0, {}, first.id));
    const request = new Request('https://adminifender.example/api/v1/briefings/current', { headers: { authorization: `Bearer ${token}` } });
    await expect(currentBriefing(request, racing, T0)).rejects.toMatchObject({ status: 409, code: 'snapshot_changed' });
    expect((await get()).newConcerns).toEqual([]);
  });

  it('bounds receipt retention and does not review an expired or concurrently removed receipt', async () => {
    for (let i = 0; i < SNAPSHOT_LIMIT + 3; i++) await env.CONTROL_DB.prepare("INSERT INTO briefings(id, snapshot_seq, through_time, included_revisions_json, created_at) VALUES (?, 0, ?, '{}', ?)").bind(crypto.randomUUID(), T0 + i, T0 + i).run();
    const current = await get(T0 + 500);
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM briefings').first<{ n: number }>())!.n).toBeLessThanOrEqual(SNAPSHOT_LIMIT);
    await expect(reviewBriefing(quietEnv(), T0 + 501 + SNAPSHOT_TTL_MS, {}, current.id)).rejects.toMatchObject({ status: 404 });
    const racing = beforeNextBatch(() => env.CONTROL_DB.prepare('DELETE FROM briefings WHERE id = ?').bind(current.id).run());
    await expect(reviewBriefing(racing, T0 + 501, {}, current.id)).rejects.toMatchObject({ status: 409, code: 'snapshot_changed' });
    expect(await env.CONTROL_DB.prepare('SELECT last_reviewed_seq FROM review_state').first()).toBeNull();
  });
});
