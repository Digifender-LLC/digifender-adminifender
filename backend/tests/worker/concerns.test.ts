import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { wakeSnoozed } from '../../src/concerns/lifecycle';
import { assertion, isAssertionFailure } from '../../src/db/assert';
import { runJob } from '../../src/jobs/runner';
import { T0, api, enrollOwner, ingest, quietEnv, runPending } from './support';

const MIN = 60_000;
const mail = (o: { subject?: string; body?: string; from?: string; id?: string; inReplyTo?: string; references?: string; to?: string }) =>
  [
    `From: ${o.from ?? 'ops@vendor.example'}`,
    `To: ${o.to ?? 'inbox@adminifender.example'}`,
    `Subject: ${o.subject ?? 'Renewal due'}`,
    ...(o.id ? [`Message-ID: ${o.id}`] : []),
    ...(o.inReplyTo ? [`In-Reply-To: ${o.inReplyTo}`] : []),
    ...(o.references ? [`References: ${o.references}`] : []),
    '',
    o.body ?? 'Your renewal is due on Friday.',
    '',
  ].join('\r\n');

let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-8000-${String((keyCount += 1)).padStart(12, '0')}`;

/** Ingests a message, runs triage and returns the source and its concern. */
async function triaged(raw: string, now = T0, to?: string): Promise<{ sourceId: string; concernId: string }> {
  const sourceId = await ingest(raw, { now, ...(to ? { to } : {}) });
  expect(await runPending('triage', now)).toEqual(['complete']);
  const row = await env.CONTROL_DB.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(sourceId).first<{ concern_id: string }>();
  return { sourceId, concernId: row!.concern_id };
}

const getConcern = async (id: string, now = T0) => (await api('GET', `/concerns/${id}`, { token, now })).body.data;
const act = (id: string, expectedRevision: number, kind: string, args?: object, now = T0) =>
  api('POST', `/concerns/${id}/actions`, { token, now, key: key(), body: { expectedRevision, kind, ...(args ? { args } : {}) } });

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('triage and grouping', () => {
  it('creates an open routine concern awaiting inference for a normal source', async () => {
    const { sourceId, concernId } = await triaged(mail({}));
    const view = await getConcern(concernId);
    expect(view.concern).toMatchObject({ title: 'Renewal due', status: 'open', importance: 'routine', revision: 1, sourceIds: [sourceId], acknowledgedAt: null, readAt: null });
    expect(view.triage).toEqual([{ sourceId, status: 'awaiting_inference', ruleImportance: null, winningRuleId: null, matchedRuleIds: [] }]);
    expect(view.controls).toEqual(['acknowledge', 'resolve', 'snooze', 'wait', 'set_due', 'mark_read']);
    expect(await env.CONTROL_DB.prepare("SELECT revision FROM changes WHERE entity_type = 'concern' AND entity_id = ?").bind(concernId).all()).toMatchObject({ results: [{ revision: 1 }] });
    // A redelivered triage job does nothing further.
    expect(await runPending('triage')).toEqual([]);
  });

  it('does not triage quarantined sources', async () => {
    const raw = 'From: a@x.example, b@y.example\r\nTo: inbox@adminifender.example\r\nSubject: Prize\r\nContent-Type: text/html\r\n\r\n<p>Claim your prize now.</p><a href="https://evil.example/">bank.example</a>\r\n';
    await ingest(raw);
    expect(await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE kind = 'triage'").first()).toEqual({ n: 0 });
  });

  it('groups reply chains in the same mailbox but not same-subject or cross-mailbox mail', async () => {
    const first = await triaged(mail({ id: '<root@vendor.example>' }));
    const reply = await triaged(mail({ subject: 'Re: Renewal due', id: '<r1@vendor.example>', inReplyTo: '<root@vendor.example>', body: 'Paid, thanks.' }), T0 + MIN);
    const deep = await triaged(mail({ subject: 'Re: Re: Renewal due', inReplyTo: '<missing@vendor.example>', references: '<root@vendor.example> <r1@vendor.example> <missing@vendor.example>', body: 'Receipt.' }), T0 + 2 * MIN);
    const sameSubject = await triaged(mail({ body: 'A different renewal entirely.' }), T0 + 3 * MIN);
    const otherMailbox = await triaged(mail({ inReplyTo: '<root@vendor.example>', body: 'Spoofed reply.' }), T0 + 4 * MIN, 'other@adminifender.example');
    expect(reply.concernId).toBe(first.concernId);
    expect(deep.concernId).toBe(first.concernId);
    expect(sameSubject.concernId).not.toBe(first.concernId);
    expect(otherMailbox.concernId).not.toBe(first.concernId);
    const view = await getConcern(first.concernId);
    expect(view.concern.sourceIds).toEqual([first.sourceId, reply.sourceId, deep.sourceId]);
    expect(view.concern.revision).toBe(3);
    expect(view.lastReportedAt).toBe(new Date(T0 + 2 * MIN).toISOString());
  });

  it('reopens a resolved concern on material evidence and keeps an ordinary snooze', async () => {
    const { concernId } = await triaged(mail({ id: '<a@vendor.example>' }));
    expect((await act(concernId, 1, 'acknowledge')).status).toBe(200);
    expect((await act(concernId, 2, 'resolve')).status).toBe(200);
    await triaged(mail({ id: '<b@vendor.example>', inReplyTo: '<a@vendor.example>', body: 'Payment failed.' }), T0 + MIN);
    expect((await getConcern(concernId)).concern).toMatchObject({ status: 'open', resolvedAt: null, acknowledgedAt: null, revision: 4 });

    expect((await act(concernId, 4, 'snooze', { until: new Date(T0 + 60 * MIN).toISOString() }, T0 + MIN)).status).toBe(200);
    await triaged(mail({ id: '<c@vendor.example>', inReplyTo: '<b@vendor.example>', body: 'Second reminder.' }), T0 + 2 * MIN);
    expect((await getConcern(concernId)).concern).toMatchObject({ status: 'snoozed', revision: 6 });
  });

  it('resurfaces a snoozed concern for a rule-confirmed critical update', async () => {
    const created = await api('POST', '/rules', { token, key: key(), body: { predicate: { subject_contains: 'outage' }, effects: [{ critical: true }] } });
    expect(created.status).toBe(200);
    const { concernId } = await triaged(mail({ id: '<a@vendor.example>' }));
    await act(concernId, 1, 'snooze', { until: new Date(T0 + 60 * MIN).toISOString() });
    await triaged(mail({ subject: 'Re: outage', inReplyTo: '<a@vendor.example>', body: 'Service down.' }), T0 + MIN);
    const view = await getConcern(concernId);
    expect(view.concern).toMatchObject({ status: 'open', snoozeUntil: null, importance: 'critical', criticalBasis: `rule:${created.body.data.id}` });
  });

  it('enqueues triage when a quarantined source is released', async () => {
    const raw = 'From: a@x.example, b@y.example\r\nTo: inbox@adminifender.example\r\nSubject: Prize\r\nContent-Type: text/html\r\n\r\n<p>Claim your prize now.</p><a href="https://evil.example/">bank.example</a>\r\n';
    const sourceId = await ingest(raw);
    const released = await api('POST', `/sources/${sourceId}/release`, { token, key: key(), body: { expectedRevision: 2 } });
    expect(released.status).toBe(200);
    expect(await runPending('triage')).toEqual(['complete']);
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM concern_sources WHERE source_id = ?').bind(sourceId).first()).toEqual({ n: 1 });
  });
});

describe('rules API', () => {
  it('lists, validates and updates owner rules', async () => {
    const bad = await api('POST', '/rules', { token, key: key(), body: { predicate: { regex: '.*' }, effects: [{ surface: true }] } });
    expect(bad.status).toBe(400);
    expect(bad.body.error?.details).toMatchObject({ path: '$.predicate.regex', reason: 'unknown_operator' });
    const unknownService = await api('POST', '/rules', { token, key: key(), body: { predicate: { sender_equals: 'a@b.example' }, effects: [{ assign_service: crypto.randomUUID() }] } });
    expect(unknownService.status).toBe(422);

    const created = await api('POST', '/rules', { token, key: key(), body: { predicate: { domain_equals: 'Vendor.Example' }, effects: [{ surface: true }] } });
    expect(created.body.data).toMatchObject({ origin: 'owner', enabled: true, revision: 1, predicate: { domain_equals: 'vendor.example' } });
    const id = created.body.data.id;
    expect((await api('PATCH', `/rules/${id}`, { token, key: key(), body: { expectedRevision: 2, enabled: false } })).status).toBe(409);
    const disabled = await api('PATCH', `/rules/${id}`, { token, key: key(), body: { expectedRevision: 1, enabled: false } });
    expect(disabled.body.data).toMatchObject({ enabled: false, revision: 2 });
    expect((await api('GET', '/rules', { token })).body.data.rules).toHaveLength(1);
  });

  it('applies changed rules only to sources triaged afterwards', async () => {
    const before = await triaged(mail({}));
    await api('POST', '/rules', { token, key: key(), body: { predicate: { domain_equals: 'vendor.example' }, effects: [{ surface: true }] } });
    const after = await triaged(mail({ body: 'Another notice.' }), T0 + MIN);
    expect((await getConcern(before.concernId)).concern.importance).toBe('routine');
    expect((await getConcern(after.concernId)).concern.importance).toBe('important');
  });

  it('keeps learned rules read-only apart from enabling', async () => {
    const id = crypto.randomUUID();
    await env.CONTROL_DB.prepare("INSERT INTO rules(id, revision, origin, enabled, predicate_json, effect_json, created_at) VALUES (?, 1, 'learned', 1, '{\"sender_equals\":\"a@b.example\"}', '[{\"surface\":true}]', 0)").bind(id).run();
    expect((await api('PATCH', `/rules/${id}`, { token, key: key(), body: { expectedRevision: 1, effects: [{ critical: true }] } })).status).toBe(422);
    expect((await api('PATCH', `/rules/${id}`, { token, key: key(), body: { expectedRevision: 1, enabled: false } })).status).toBe(200);
  });
});

describe('owner actions', () => {
  it('records events and changes, and only meaningful edits advance review time', async () => {
    const { concernId } = await triaged(mail({}));
    const read = await act(concernId, 1, 'mark_read', undefined, T0 + MIN);
    expect(read.body.data).toMatchObject({ concern: { status: 'open', revision: 2 }, undoableUntil: null });
    expect(await env.CONTROL_DB.prepare('SELECT last_meaningful_at FROM review_state').first()).toBeNull();
    const ack = await act(concernId, 2, 'acknowledge', undefined, T0 + 2 * MIN);
    expect(ack.body.data.concern).toMatchObject({ status: 'open', acknowledgedAt: new Date(T0 + 2 * MIN).toISOString() });
    expect(await env.CONTROL_DB.prepare('SELECT last_meaningful_at FROM review_state').first()).toEqual({ last_meaningful_at: T0 + 2 * MIN });
    const changes = await env.CONTROL_DB.prepare("SELECT revision FROM changes WHERE entity_type = 'concern' AND entity_id = ? ORDER BY seq").bind(concernId).all<{ revision: number }>();
    expect(changes.results.map((r) => r.revision)).toEqual([1, 2, 3]);
  });

  it('validates arguments, revisions and transitions', async () => {
    const { concernId } = await triaged(mail({}));
    expect((await act(concernId, 1, 'snooze', { until: new Date(T0 - 1).toISOString() })).status).toBe(400);
    expect((await act(concernId, 1, 'snooze')).status).toBe(400);
    expect((await act(concernId, 1, 'resolve', { extra: true })).status).toBe(400);
    expect((await act(concernId, 1, 'set_due', { at: new Date(T0).toISOString(), zone: 'Mars/Olympus' })).status).toBe(400);
    expect((await act(concernId, 9, 'resolve')).status).toBe(409);
    expect((await act(concernId, 1, 'reopen')).status).toBe(422);
    const due = await act(concernId, 1, 'set_due', { at: '2026-10-09T15:00:00Z', zone: 'Europe/Berlin' });
    expect(due.body.data.concern.due).toEqual({ at: '2026-10-09T15:00:00.000Z', zone: 'Europe/Berlin', sourceText: null, revision: 1 });
    const wait = await act(concernId, 2, 'wait', { note: 'Asked finance', awaitedParty: 'Finance', followUpAt: '2026-10-07T09:00:00Z' });
    expect(wait.body.data.concern).toMatchObject({ status: 'waiting', waiting: { note: 'Asked finance', awaitedParty: 'Finance', followUpAt: '2026-10-07T09:00:00.000Z' }, due: { at: '2026-10-09T15:00:00.000Z' } });
  });

  it('replays an action with the same idempotency key instead of applying it twice', async () => {
    const { concernId } = await triaged(mail({}));
    const k = key();
    const body = { expectedRevision: 1, kind: 'acknowledge' };
    const first = await api('POST', `/concerns/${concernId}/actions`, { token, key: k, body });
    const second = await api('POST', `/concerns/${concernId}/actions`, { token, key: k, body, now: T0 + 1000 });
    expect(second.body.data).toEqual(first.body.data);
    expect((await getConcern(concernId)).concern.revision).toBe(2);
  });
});

describe('undo', () => {
  it('restores a recent review edit at its exact resulting revision only', async () => {
    const { concernId } = await triaged(mail({}));
    const resolved = await act(concernId, 1, 'resolve');
    const eventId = resolved.body.data.eventId;
    expect(resolved.body.data.undoableUntil).toBe(new Date(T0 + 10 * MIN).toISOString());

    const undone = await api('POST', `/concerns/${concernId}/undo`, { token, key: key(), body: { eventId, resultingRevision: 2 }, now: T0 + MIN });
    expect(undone.status).toBe(200);
    expect(undone.body.data.concern).toMatchObject({ status: 'open', resolvedAt: null, revision: 3 });
    // The edit is already superseded by the undo itself.
    expect((await api('POST', `/concerns/${concernId}/undo`, { token, key: key(), body: { eventId, resultingRevision: 2 }, now: T0 + MIN })).status).toBe(409);
  });

  it('conflicts after later work and expires after 10 minutes', async () => {
    const { concernId } = await triaged(mail({}));
    const ack = await act(concernId, 1, 'acknowledge');
    await act(concernId, 2, 'snooze', { until: new Date(T0 + 60 * MIN).toISOString() });
    expect((await api('POST', `/concerns/${concernId}/undo`, { token, key: key(), body: { eventId: ack.body.data.eventId, resultingRevision: 2 } })).status).toBe(409);

    const view = await getConcern(concernId);
    const snoozeEvent = view.events[0];
    expect(snoozeEvent).toMatchObject({ kind: 'snooze', toRevision: 3 });
    expect((await api('POST', `/concerns/${concernId}/undo`, { token, key: key(), body: { eventId: snoozeEvent.id, resultingRevision: 3 }, now: T0 + 11 * MIN })).status).toBe(422);
  });

  it('does not undo reads', async () => {
    const { concernId } = await triaged(mail({}));
    const read = await act(concernId, 1, 'mark_read');
    expect((await api('POST', `/concerns/${concernId}/undo`, { token, key: key(), body: { eventId: read.body.data.eventId, resultingRevision: 2 } })).status).toBe(422);
  });
});

describe('listing', () => {
  it('filters by status and importance and pages with a cursor', async () => {
    const a = await triaged(mail({ body: 'one' }));
    const b = await triaged(mail({ body: 'two' }), T0 + MIN);
    await act(a.concernId, 1, 'resolve', undefined, T0 + 2 * MIN);
    const open = await api('GET', '/concerns?status=open', { token });
    expect(open.body.data.concerns.map((c: { id: string }) => c.id)).toEqual([b.concernId]);
    const page1 = await api('GET', '/concerns?limit=1', { token });
    expect(page1.body.data.concerns.map((c: { id: string }) => c.id)).toEqual([a.concernId]);
    const page2 = await api('GET', `/concerns?limit=1&cursor=${encodeURIComponent(page1.body.data.nextCursor)}`, { token });
    expect(page2.body.data.concerns.map((c: { id: string }) => c.id)).toEqual([b.concernId]);
    expect((await api('GET', '/concerns?status=closed', { token })).status).toBe(400);
    expect((await api('GET', '/concerns', {})).status).toBe(401);
  });
});

describe('snooze expiry and conflicts', () => {
  it('returns due snoozes to open as a new revision', async () => {
    const { concernId } = await triaged(mail({}));
    await act(concernId, 1, 'snooze', { until: new Date(T0 + 30 * MIN).toISOString() });
    expect(await wakeSnoozed(env.CONTROL_DB, T0 + 29 * MIN)).toEqual({ woken: 0 });
    expect(await wakeSnoozed(env.CONTROL_DB, T0 + 30 * MIN)).toEqual({ woken: 1 });
    expect((await getConcern(concernId)).concern).toMatchObject({ status: 'open', snoozeUntil: null, revision: 3 });
  });

  it('aborts a whole batch when an assertion fails', async () => {
    const db = env.CONTROL_DB;
    const failure = await db
      .batch([db.prepare("INSERT INTO rate_limits(key, window_start, count) VALUES ('k', 0, 1)"), assertion(db, 'EXISTS (SELECT 1 FROM concerns WHERE id = ?)', 'missing')])
      .catch((error: unknown) => error);
    expect(isAssertionFailure(failure)).toBe(true);
    expect(await db.prepare("SELECT COUNT(*) AS n FROM rate_limits WHERE key = 'k'").first()).toEqual({ n: 0 });
  });

  it('retries a job whose commit lost a revision race', async () => {
    const db = env.CONTROL_DB;
    await db.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES ('6a1f0e7c-1b2d-4c3e-8f40-5a6b7c8d9e0f', 'race', 'race', 'pending', 0, '{}', 0)").run();
    const handlers = { race: async () => ({ kind: 'complete' as const, output: null, statements: [assertion(db, '0')] }) };
    expect(await runJob(quietEnv(), handlers, '6a1f0e7c-1b2d-4c3e-8f40-5a6b7c8d9e0f', T0)).toEqual({ jobId: '6a1f0e7c-1b2d-4c3e-8f40-5a6b7c8d9e0f', status: 'retry' });
    expect(await db.prepare("SELECT state, error_code FROM jobs WHERE dedupe_key = 'race'").first()).toEqual({ state: 'pending', error_code: 'commit_conflict' });
  });
});
