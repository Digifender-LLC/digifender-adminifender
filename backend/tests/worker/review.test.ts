import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import fixture from '../../../contracts/fixtures/v1/briefing/valid/four-sections.json';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const MIN = 60_000;
const mail = (o: { subject?: string; body?: string; from?: string; id?: string; inReplyTo?: string }) =>
  [
    `From: ${o.from ?? 'ops@vendor.example'}`,
    'To: inbox@adminifender.example',
    `Subject: ${o.subject ?? 'Renewal due'}`,
    ...(o.id ? [`Message-ID: ${o.id}`] : []),
    ...(o.inReplyTo ? [`In-Reply-To: ${o.inReplyTo}`] : []),
    '',
    o.body ?? 'Your renewal is due on Friday.',
    '',
  ].join('\r\n');

let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-9000-${String((keyCount += 1)).padStart(12, '0')}`;

async function triaged(raw: string, now = T0): Promise<{ sourceId: string; concernId: string }> {
  const sourceId = await ingest(raw, { now });
  await runPending('triage', now);
  const row = await env.CONTROL_DB.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(sourceId).first<{ concern_id: string }>();
  return { sourceId, concernId: row!.concern_id };
}
const post = (path: string, body: object, now = T0) => api('POST', path, { token, key: key(), body, now });
const concern = async (id: string) => (await api('GET', `/concerns/${id}`, { token })).body.data;
const ids = (list: { id: string }[]) => list.map((c) => c.id);

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('corrections', () => {
  it('edits this concern and previews, without saving, a sender or domain rule', async () => {
    const { concernId } = await triaged(mail({ from: 'Billing <Alerts@Vendor.Example>' }));
    const corrected = await post(`/concerns/${concernId}/corrections`, { expectedRevision: 1, importance: 'important', applyTo: 'sender', reason: 'Billing matters' });
    expect(corrected.status).toBe(200);
    expect(corrected.body.data.concern).toMatchObject({ importance: 'important', revision: 2 });
    expect(corrected.body.data.rulePreview).toEqual({ predicate: { sender_equals: 'Alerts@vendor.example' }, effects: [{ surface: true }] });
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM rules').first()).toEqual({ n: 0 });

    const domain = await post(`/concerns/${concernId}/corrections`, { expectedRevision: 2, importance: 'critical', applyTo: 'domain' });
    expect(domain.body.data.rulePreview).toEqual({ predicate: { domain_equals: 'vendor.example' }, effects: [{ critical: true }] });
    expect(domain.body.data.concern).toMatchObject({ importance: 'critical', criticalBasis: 'owner' });

    const lowered = await post(`/concerns/${concernId}/corrections`, { expectedRevision: 3, importance: 'routine', applyTo: 'this_concern' });
    expect(lowered.body.data).toMatchObject({ concern: { importance: 'routine', criticalBasis: null }, rulePreview: null });
    expect((await post(`/concerns/${concernId}/corrections`, { expectedRevision: 3, importance: 'urgent', applyTo: 'this_concern' })).status).toBe(409);
  });

  it('does not unblock senders or change source privacy', async () => {
    const { sourceId, concernId } = await triaged(mail({}));
    await post(`/concerns/${concernId}/corrections`, { expectedRevision: 1, importance: 'important', applyTo: 'domain' });
    const source = await api('GET', `/sources/${sourceId}`, { token });
    expect(source.body.data.source).toMatchObject({ privacy: 'hybrid', admission: 'normal' });
  });
});

describe('merge and split', () => {
  it('merges with all current revisions, preserving links and history', async () => {
    const a = await triaged(mail({ body: 'one' }));
    const b = await triaged(mail({ subject: 'Renewal reminder', body: 'two' }), T0 + MIN);
    await post(`/concerns/${b.concernId}/corrections`, { expectedRevision: 1, importance: 'urgent', applyTo: 'this_concern' });

    const stale = await post('/concerns/merge', { targetId: a.concernId, concerns: [{ id: a.concernId, expectedRevision: 1 }, { id: b.concernId, expectedRevision: 1 }] });
    expect(stale.status).toBe(409);
    const merged = await post('/concerns/merge', { targetId: a.concernId, concerns: [{ id: a.concernId, expectedRevision: 1 }, { id: b.concernId, expectedRevision: 2 }] });
    expect(merged.status).toBe(200);
    expect(merged.body.data.concern).toMatchObject({ id: a.concernId, importance: 'urgent', revision: 2, sourceIds: [a.sourceId, b.sourceId] });
    const away = await concern(b.concernId);
    expect(away.concern).toMatchObject({ status: 'resolved', sourceIds: [] });
    expect(away.events[0]).toMatchObject({ kind: 'merged_away' });
    // Both sources stay in the archive.
    expect((await api('GET', `/sources/${b.sourceId}`, { token })).status).toBe(200);
  });

  it('rejects a merge whose target is not listed', async () => {
    const a = await triaged(mail({ body: 'one' }));
    const b = await triaged(mail({ body: 'two' }), T0 + MIN);
    const missing = await post('/concerns/merge', { targetId: crypto.randomUUID(), concerns: [{ id: a.concernId, expectedRevision: 1 }, { id: b.concernId, expectedRevision: 1 }] });
    expect(missing.status).toBe(400);
  });

  it('splits explicit sources into a new concern and keeps at least one behind', async () => {
    const first = await triaged(mail({ id: '<a@vendor.example>' }));
    const reply = await triaged(mail({ subject: 'Different topic', id: '<b@vendor.example>', inReplyTo: '<a@vendor.example>', body: 'Unrelated' }), T0 + MIN);
    expect(reply.concernId).toBe(first.concernId);
    expect((await post(`/concerns/${first.concernId}/split`, { expectedRevision: 2, sourceIds: [first.sourceId, reply.sourceId] })).status).toBe(422);
    expect((await post(`/concerns/${first.concernId}/split`, { expectedRevision: 2, sourceIds: [crypto.randomUUID()] })).status).toBe(422);

    const split = await post(`/concerns/${first.concernId}/split`, { expectedRevision: 2, sourceIds: [reply.sourceId] });
    expect(split.status).toBe(200);
    expect(split.body.data.concern).toMatchObject({ revision: 3, sourceIds: [first.sourceId] });
    expect(split.body.data.created).toMatchObject({ title: 'Different topic', status: 'open', revision: 1, sourceIds: [reply.sourceId] });
    expect((await concern(first.concernId)).events.map((e: { kind: string }) => e.kind)).toEqual(['split', 'evidence_added', 'created']);
  });
});

describe('briefings', () => {
  it('shows new work, then only changes after Mark reviewed, across two reviews', async () => {
    const a = await triaged(mail({ id: '<a@vendor.example>' }));
    const first = (await api('GET', '/briefings/current', { token, now: T0 + MIN })).body.data;
    expect(ids(first.newConcerns)).toEqual([a.concernId]);
    expect(first.newSourceIds).toEqual([a.sourceId]);
    expect(ids(first.unacknowledged)).toEqual([a.concernId]);
    // Opening it again reuses the same snapshot.
    expect((await api('GET', '/briefings/current', { token, now: T0 + MIN })).body.data.id).toBe(first.id);

    const reviewed = await post(`/briefings/${first.id}/review`, {}, T0 + 2 * MIN);
    expect(reviewed.body.data).toMatchObject({ reviewedThroughSeq: first.snapshotSeq });
    expect((await post(`/briefings/${first.id}/review`, {}, T0 + 2 * MIN)).status).toBe(409);
    expect((await concern(a.concernId)).concern).toMatchObject({ status: 'open', acknowledgedAt: null });

    const second = (await api('GET', '/briefings/current', { token, now: T0 + 3 * MIN })).body.data;
    expect(second.newConcerns).toEqual([]);
    expect(second.changedConcerns).toEqual([]);
    expect(ids(second.unacknowledged)).toEqual([a.concernId]);
    expect(second.lastReviewedAt).toBe(new Date(T0 + 2 * MIN).toISOString());

    await post(`/concerns/${a.concernId}/actions`, { expectedRevision: 1, kind: 'acknowledge' }, T0 + 4 * MIN);
    await triaged(mail({ id: '<b@vendor.example>', inReplyTo: '<a@vendor.example>', body: 'Price changed.' }), T0 + 5 * MIN);
    const b = await triaged(mail({ subject: 'New topic', body: 'Something else.' }), T0 + 6 * MIN);
    const third = (await api('GET', '/briefings/current', { token, now: T0 + 7 * MIN })).body.data;
    expect(ids(third.changedConcerns)).toEqual([a.concernId]);
    expect(ids(third.newConcerns)).toEqual([b.concernId]);
    expect(third.newSourceIds).toHaveLength(2);
  });

  it('matches the shared briefing fixture the phone parses', async () => {
    const a = await triaged(mail({}));
    const live = (await api('GET', '/briefings/current', { token, now: T0 + MIN })).body.data;
    expect(Object.keys(live).sort()).toEqual(Object.keys(fixture).sort());
    expect(live.summary).toBeNull();
    for (const section of ['newConcerns', 'unacknowledged'] as const) {
      expect(ids(live[section])).toEqual([a.concernId]);
      expect(Object.keys(live[section][0]).sort()).toEqual(Object.keys(fixture[section][0]!).sort());
    }
  });

  it('replays a resent review with the same key and reports already_reviewed for a new key', async () => {
    await triaged(mail({}));
    const briefing = (await api('GET', '/briefings/current', { token, now: T0 + MIN })).body.data;
    const resendKey = key();
    const first = await api('POST', `/briefings/${briefing.id}/review`, { token, key: resendKey, body: {}, now: T0 + 2 * MIN });
    const resent = await api('POST', `/briefings/${briefing.id}/review`, { token, key: resendKey, body: {}, now: T0 + 3 * MIN });
    expect(resent.status).toBe(200);
    expect(resent.body.data).toEqual(first.body.data);
    const fresh = await post(`/briefings/${briefing.id}/review`, {}, T0 + 4 * MIN);
    expect(fresh.status).toBe(409);
    expect(fresh.body.error?.code).toBe('already_reviewed');
    expect((await post('/briefings/4f8c0d2e-6a71-4c93-8e3f-5a6b7c8d9e0f/review', {}, T0 + 5 * MIN)).status).toBe(404);
  });

  it('reviewing an older snapshot never covers later changes', async () => {
    await triaged(mail({ body: 'one' }));
    const older = (await api('GET', '/briefings/current', { token, now: T0 + MIN })).body.data;
    const later = await triaged(mail({ subject: 'Later', body: 'two' }), T0 + 2 * MIN);
    await post(`/briefings/${older.id}/review`, {}, T0 + 3 * MIN);
    const current = (await api('GET', '/briefings/current', { token, now: T0 + 4 * MIN })).body.data;
    expect(ids(current.newConcerns)).toEqual([later.concernId]);
  });

  it('lists overdue unresolved concerns by backend UTC time', async () => {
    const { concernId } = await triaged(mail({}));
    await post(`/concerns/${concernId}/actions`, { expectedRevision: 1, kind: 'set_due', args: { at: new Date(T0 + 5 * MIN).toISOString(), zone: 'America/New_York' } });
    expect((await api('GET', '/briefings/current', { token, now: T0 + 4 * MIN })).body.data.overdue).toEqual([]);
    const late = (await api('GET', '/briefings/current', { token, now: T0 + 6 * MIN })).body.data;
    expect(ids(late.overdue)).toEqual([concernId]);
    expect(late.overdue[0].due).toMatchObject({ zone: 'America/New_York' });
  });
});

describe('briefings over the section caps', () => {
  const db = () => env.CONTROL_DB;
  const SEED_TIME = T0 - 60 * MIN;
  const uuid = () => crypto.randomUUID();
  const run = async (statements: D1PreparedStatement[]) => {
    for (let i = 0; i < statements.length; i += 100) await db().batch(statements.slice(i, i + 100));
  };
  /** A concern row with its events and one change row per revision, as the triage job would leave it. */
  const concernRevisions = (id: string, kinds: string[]): D1PreparedStatement[] => [
    db().prepare("INSERT INTO concerns(id, revision, title, status, importance, due_revision, acknowledged_at, updated_at) VALUES (?, ?, ?, 'open', 'routine', 0, ?, ?)").bind(id, kinds.length, `Concern ${id}`, SEED_TIME, SEED_TIME),
    ...kinds.flatMap((kind, i) => [
      db().prepare("INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) VALUES (?, ?, ?, ?, ?, '{}', '{}', ?)").bind(uuid(), id, i, i + 1, kind, SEED_TIME),
      db().prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('concern', ?, ?, 0, '{}', ?)").bind(id, i + 1, SEED_TIME),
    ]),
  ];
  const readySource = (id: string, state = 'ready'): D1PreparedStatement[] => [
    db().prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('source', ?, 1, 0, '{}', ?)").bind(id, SEED_TIME),
    db().prepare("INSERT INTO source_registry(id, kind, external_key, content_hash, admission, privacy, state, revision, received_at, ready_seq) VALUES (?, 'email', ?, 'h', 'normal', 'hybrid', ?, 1, ?, (SELECT MAX(seq) FROM changes))").bind(id, id, state, SEED_TIME),
  ];
  const setCursor = (seq: number) =>
    db().prepare("INSERT INTO review_state(id, last_meaningful_at, last_reviewed_seq, review_epoch) VALUES ('owner', ?, ?, 'v1') ON CONFLICT(id) DO UPDATE SET last_reviewed_seq = excluded.last_reviewed_seq").bind(SEED_TIME, seq).run();
  const maxSeq = async () => (await db().prepare('SELECT MAX(seq) AS seq FROM changes').first<{ seq: number }>())!.seq;

  /** Opens and reviews briefings until one is complete, returning every briefing it showed. */
  async function drain(limit = 10) {
    const shown: any[] = [];
    for (let round = 0; round < limit; round += 1) {
      const briefing = (await api('GET', '/briefings/current', { token, now: T0 + round * 2_000 })).body.data;
      shown.push(briefing);
      const review = await post(`/briefings/${briefing.id}/review`, {}, T0 + round * 2_000 + 1_000);
      expect(review.body.data).toMatchObject({ reviewedThroughSeq: briefing.snapshotSeq });
      if (!briefing.hasMore) return shown;
    }
    throw new Error('briefing never completed');
  }
  const includedFor = async (briefingId: string) =>
    Object.keys(JSON.parse((await db().prepare('SELECT included_revisions_json AS j FROM briefings WHERE id = ?').bind(briefingId).first<{ j: string }>())!.j));

  it('shows more than 100 new sources over successive reviews without skipping any', async () => {
    const sources = Array.from({ length: 230 }, uuid);
    await run(sources.flatMap((id, i) => readySource(id, i === 7 ? 'deleted' : 'ready')));
    const eligible = sources.filter((_, i) => i !== 7);
    const sequence = new Map(
      (await db().prepare('SELECT id, ready_seq FROM source_registry').all<{ id: string; ready_seq: number }>()).results.map((r) => [r.id, r.ready_seq]),
    );

    const first = (await api('GET', '/briefings/current', { token, now: T0 })).body.data;
    expect(first.newSourceIds).toHaveLength(100);
    expect(first.hasMore).toBe(true);
    // The cursor stops before the oldest source left out, so reviewing this snapshot cannot cover it.
    const omitted = eligible.filter((id) => !first.newSourceIds.includes(id));
    expect(first.snapshotSeq).toBe(Math.min(...omitted.map((id) => sequence.get(id)!)) - 1);
    expect(first.snapshotSeq).toBeLessThan(await maxSeq());
    expect(new Date(first.throughTime).getTime()).toBeLessThanOrEqual(SEED_TIME);

    await post(`/briefings/${first.id}/review`, {}, T0 + 1_000);
    const rest = await drain();
    const seen = [first, ...rest].flatMap((b) => b.newSourceIds);
    expect(new Set(seen).size).toBe(seen.length);
    expect([...seen].sort()).toEqual([...eligible].sort());
    expect(rest.map((b) => b.newSourceIds.length)).toEqual([100, 29]);
    expect(rest.at(-1).hasMore).toBe(false);
    expect(rest.at(-1).snapshotSeq).toBe(await maxSeq());
    // Nothing is left once everything was reviewed.
    expect((await api('GET', '/briefings/current', { token, now: T0 + 60_000 })).body.data).toMatchObject({ newSourceIds: [], hasMore: false });
  });

  it('shows more than 200 material concerns over successive reviews, each card listed as included', async () => {
    const created = Array.from({ length: 250 }, uuid);
    await run(created.flatMap((id) => concernRevisions(id, ['created'])));

    const first = (await api('GET', '/briefings/current', { token, now: T0 })).body.data;
    expect(first.newConcerns).toHaveLength(100);
    expect(first.hasMore).toBe(true);
    expect(first.snapshotSeq).toBeLessThan(await maxSeq());
    expect([...(await includedFor(first.id))].sort()).toEqual(ids(first.newConcerns).sort());
    // Newest first inside the covered interval, and nothing newer than the cursor is shown.
    const changeSeq = new Map((await db().prepare("SELECT entity_id AS id, seq FROM changes WHERE entity_type = 'concern'").all<{ id: string; seq: number }>()).results.map((r) => [r.id, r.seq]));
    expect(ids(first.newConcerns).map((id) => changeSeq.get(id)!)).toEqual(created.slice(0, 100).map((id) => changeSeq.get(id)!).reverse());
    expect(Math.max(...ids(first.newConcerns).map((id) => changeSeq.get(id)!))).toBeLessThanOrEqual(first.snapshotSeq);

    await post(`/briefings/${first.id}/review`, {}, T0 + 1_000);
    const second = (await api('GET', '/briefings/current', { token, now: T0 + 2_000 })).body.data;
    expect(ids(second.newConcerns)).toEqual(created.slice(100, 200).reverse());
    expect(second.hasMore).toBe(true);
    await post(`/briefings/${second.id}/review`, {}, T0 + 3_000);
    const third = (await api('GET', '/briefings/current', { token, now: T0 + 4_000 })).body.data;
    expect(ids(third.newConcerns)).toEqual(created.slice(200).reverse());
    expect(third.hasMore).toBe(false);
    expect(third.snapshotSeq).toBe(await maxSeq());
  });

  it('caps changed concerns separately from new ones', async () => {
    const changed = Array.from({ length: 140 }, uuid);
    const fresh = Array.from({ length: 3 }, uuid);
    await run(changed.flatMap((id) => concernRevisions(id, ['created'])));
    await setCursor(await maxSeq());
    // The new concerns arrive between the changes.
    await run([
      ...changed.slice(0, 70).flatMap((id) => [
        db().prepare("UPDATE concerns SET revision = 2 WHERE id = ?").bind(id),
        db().prepare("INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) VALUES (?, ?, 1, 2, 'evidence_added', '{}', '{}', ?)").bind(uuid(), id, SEED_TIME),
        db().prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('concern', ?, 2, 0, '{}', ?)").bind(id, SEED_TIME),
      ]),
      ...fresh.flatMap((id) => concernRevisions(id, ['created'])),
      ...changed.slice(70).flatMap((id) => [
        db().prepare("UPDATE concerns SET revision = 2 WHERE id = ?").bind(id),
        db().prepare("INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) VALUES (?, ?, 1, 2, 'evidence_added', '{}', '{}', ?)").bind(uuid(), id, SEED_TIME),
        db().prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('concern', ?, 2, 0, '{}', ?)").bind(id, SEED_TIME),
      ]),
    ]);

    const shown = await drain();
    expect(shown).toHaveLength(2);
    expect(shown[0].hasMore).toBe(true);
    expect(shown[0].changedConcerns).toHaveLength(100);
    // The first review stops before the 101st changed concern, so the three new concerns that arrived earlier are in it.
    expect(ids(shown[0].newConcerns).sort()).toEqual([...fresh].sort());
    expect(ids(shown[0].changedConcerns)).toEqual(changed.slice(0, 70).concat(changed.slice(70, 100)).reverse());
    expect(ids(shown[1].changedConcerns)).toEqual(changed.slice(100).reverse());
    expect(shown[1].newConcerns).toEqual([]);
    expect(shown[1].hasMore).toBe(false);
    for (const b of shown) expect(new Set(await includedFor(b.id))).toEqual(new Set([...ids(b.newConcerns), ...ids(b.changedConcerns), ...ids(b.overdue), ...ids(b.unacknowledged)]));
  });

  it('keeps the cursor on an unreviewed capped snapshot, and a later reviewed older one still stops at its own boundary', async () => {
    const sources = Array.from({ length: 130 }, uuid);
    await run(sources.flatMap((id) => readySource(id)));
    const first = (await api('GET', '/briefings/current', { token, now: T0 })).body.data;
    // A source arriving after the snapshot is not covered by reviewing it.
    const late = uuid();
    await run(readySource(late));
    expect((await api('GET', '/briefings/current', { token, now: T0 + 500 })).body.data.id).toBe(first.id);
    await post(`/briefings/${first.id}/review`, {}, T0 + 1_000);
    const rest = await drain();
    const seen = [first, ...rest].flatMap((b) => b.newSourceIds);
    expect([...seen].sort()).toEqual([...sources, late].sort());
  });

  it('binds at most 100 variables per statement when all four sections are full', async () => {
    const created = Array.from({ length: 100 }, uuid);
    const changed = Array.from({ length: 100 }, uuid);
    const overdue = Array.from({ length: 100 }, uuid);
    const unacknowledged = Array.from({ length: 100 }, uuid);
    await run(changed.flatMap((id) => concernRevisions(id, ['created'])));
    await setCursor(await maxSeq());
    await run([
      ...created.flatMap((id) => concernRevisions(id, ['created'])),
      ...changed.flatMap((id) => [
        db().prepare('UPDATE concerns SET revision = 2 WHERE id = ?').bind(id),
        db().prepare("INSERT INTO concern_events(id, concern_id, from_revision, to_revision, kind, before_json, after_json, created_at) VALUES (?, ?, 1, 2, 'evidence_added', '{}', '{}', ?)").bind(uuid(), id, SEED_TIME),
        db().prepare("INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) VALUES ('concern', ?, 2, 0, '{}', ?)").bind(id, SEED_TIME),
      ]),
      ...overdue.map((id) => db().prepare("INSERT INTO concerns(id, revision, title, status, importance, due_revision, due_at, acknowledged_at, updated_at) VALUES (?, 1, 'Overdue', 'open', 'routine', 1, ?, ?, ?)").bind(id, SEED_TIME, SEED_TIME, SEED_TIME)),
      ...unacknowledged.map((id) => db().prepare("INSERT INTO concerns(id, revision, title, status, importance, due_revision, updated_at) VALUES (?, 1, 'Unacknowledged', 'open', 'routine', 0, ?)").bind(id, SEED_TIME)),
    ]);
    // Every statement the route prepares is recorded with its bound-variable count (D1 allows 100 per statement).
    const bound: number[] = [];
    const real = env.CONTROL_DB;
    const spy = new Proxy(real, {
      get(target, prop) {
        if (prop !== 'prepare') return Reflect.get(target, prop).bind(target);
        return (sql: string) => {
          const statement = target.prepare(sql);
          return new Proxy(statement, {
            get(inner, member) {
              if (member === 'bind') return (...args: unknown[]) => { bound.push(args.length); return inner.bind(...args); };
              return Reflect.get(inner, member).bind(inner);
            },
          });
        };
      },
    });

    const res = await api('GET', '/briefings/current', { token, now: T0, env: { ...env, CONTROL_DB: spy } as Env });
    expect(res.status).toBe(200);
    const briefing = res.body.data;
    expect([briefing.newConcerns, briefing.changedConcerns, briefing.overdue, briefing.unacknowledged].map((list) => list.length)).toEqual([100, 100, 100, 100]);
    expect(briefing.hasMore).toBe(false);
    const all = [...ids(briefing.newConcerns), ...ids(briefing.changedConcerns), ...ids(briefing.overdue), ...ids(briefing.unacknowledged)];
    expect(new Set(all).size).toBe(400);
    expect([...(await includedFor(briefing.id))].sort()).toEqual([...all].sort());
    expect(bound.length).toBeGreaterThan(8);
    expect(Math.max(...bound)).toBeLessThanOrEqual(100);
  });
});
