import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
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
