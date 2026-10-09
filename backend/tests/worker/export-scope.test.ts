import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { expireExports } from '../../src/exports/job';
import { inflateEntry, readCentralDirectory } from '../../src/extraction/zip';
import { T0, api, enrollOwner, expectContract, expectContractViolation, ingest, runPending } from './support';

const MIN = 60_000;
let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-b000-${String((keyCount += 1)).padStart(12, '0')}`;
const decoder = new TextDecoder();

const mail = (o: { subject: string; body?: string; from?: string; to?: string; id?: string; inReplyTo?: string }) =>
  [
    `From: ${o.from ?? 'ops@vendor.example'}`,
    `To: ${o.to ?? 'inbox@adminifender.example'}`,
    `Subject: ${o.subject}`,
    ...(o.id ? [`Message-ID: ${o.id}`] : []),
    ...(o.inReplyTo ? [`In-Reply-To: ${o.inReplyTo}`] : []),
    '',
    o.body ?? `Body of ${o.subject}`,
    '',
  ].join('\r\n');

async function unzip(bytes: Uint8Array): Promise<Map<string, Uint8Array>> {
  const out = new Map<string, Uint8Array>();
  for (const entry of readCentralDirectory(bytes)) out.set(entry.name, await inflateEntry(bytes, entry, 20 * 1024 * 1024));
  return out;
}
const lines = (data: Uint8Array | undefined) => decoder.decode(data).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
const everything = (files: Map<string, Uint8Array>) => [...files.values()].map((f) => decoder.decode(f)).join('\n');

const create = (body: unknown, options: { now?: number; key?: string } = {}) => api('POST', '/exports', { token, key: options.key ?? key(), body, now: options.now ?? T0 });
const status = async (id: string, now = T0) => {
  const res = await api('GET', `/exports/${id}`, { token, now });
  expect(res.status).toBe(200);
  expectContract('export-status', res.body.data);
  return res.body.data;
};
async function built(body: unknown, now = T0) {
  const created = await create(body, { now });
  expect(created.status).toBe(200);
  expectContract('export-status', created.body.data);
  const id = created.body.data.id as string;
  expect(await runPending('export', now)).toEqual(['complete']);
  return { id, created: created.body.data, status: await status(id, now) };
}
async function files(id: string, now = T0) {
  const res = await api('GET', `/exports/${id}/content`, { token, now });
  expect(res.status).toBe(200);
  return unzip(res.body.data as Uint8Array);
}
const sourcesOf = async (id: string) => lines((await files(id)).get('sources.jsonl')).map((s: { id: string }) => s.id);
async function quarantine(sourceId: string) {
  const revision = (await api('GET', `/sources/${sourceId}`, { token })).body.data.source.revision;
  const res = await api('POST', `/sources/${sourceId}/quarantine`, { token, key: key(), body: { expectedRevision: revision } });
  expect(res.status).toBe(200);
}
async function release(sourceId: string) {
  const revision = (await api('GET', `/sources/${sourceId}`, { token })).body.data.source.revision;
  const res = await api('POST', `/sources/${sourceId}/release`, { token, key: key(), body: { expectedRevision: revision } });
  expect(res.status).toBe(200);
}
async function deleteSources(sourceIds: string[]) {
  const review = (await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds } } })).body.data;
  const done = await api('POST', `/reviews/${review.reviewId}/confirm`, { token, key: key(), body: { paramsDigest: review.paramsDigest } });
  expect(done.status).toBe(200);
}

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('export scope: quarantine is never implied', () => {
  it('excludes quarantined mail by default and says so in the scope it records', async () => {
    const normal = await ingest(mail({ subject: 'Invoice', body: 'ordinary words' }));
    const held = await ingest(mail({ subject: 'Held mail', body: 'quarantined-marker-7731' }), { now: T0 + 1 });
    await quarantine(held);

    const { id, created, status: state } = await built({});
    expect(created.scope).toEqual({ kind: 'archive', admission: 'normal' });
    expect(state).toMatchObject({ state: 'ready', scope: { kind: 'archive', admission: 'normal' }, counts: { sources: 1 }, omissions: [] });
    const zip = await files(id);
    expect(lines(zip.get('sources.jsonl')).map((s: { id: string }) => s.id)).toEqual([normal]);
    const all = everything(zip);
    expect(all).not.toContain('quarantined-marker-7731');
    expect(all).not.toContain('Held mail');
    expect([...zip.keys()].some((path) => path.includes(held))).toBe(false);
    expect(JSON.parse(decoder.decode(zip.get('manifest.json'))).scope).toEqual({ kind: 'archive', admission: 'normal' });
  });

  it('includes quarantine only for an explicit admission, and only quarantine when asked', async () => {
    const normal = await ingest(mail({ subject: 'Invoice' }));
    const held = await ingest(mail({ subject: 'Held mail', body: 'quarantined-marker-7731' }), { now: T0 + 1 });
    await quarantine(held);

    const both = await built({ scope: { kind: 'archive', admission: 'all' } });
    expect((await sourcesOf(both.id)).sort()).toEqual([normal, held].sort());
    const only = await built({ scope: { kind: 'archive', admission: 'quarantine' } });
    expect(await sourcesOf(only.id)).toEqual([held]);
    expect(only.status.counts).toMatchObject({ sources: 1 });
  });

  it('uses the admission a source had at the snapshot, then rechecks the current one', async () => {
    const stays = await ingest(mail({ subject: 'Stays normal' }));
    const lateHeld = await ingest(mail({ subject: 'Held after the snapshot', body: 'late-marker-1' }), { now: T0 + 1 });
    const early = await ingest(mail({ subject: 'Held at the snapshot', body: 'early-marker-2' }), { now: T0 + 2 });
    await quarantine(early);

    const normal = await create({});
    const quarantined = await create({ scope: { kind: 'archive', admission: 'quarantine' } });
    // After the snapshots and before the builds: one source is quarantined, the other is released.
    await quarantine(lateHeld);
    await release(early);
    expect(await runPending('export')).toEqual(['complete', 'complete']);

    const normalStatus = await status(normal.body.data.id);
    expect(await sourcesOf(normal.body.data.id)).toEqual([stays]);
    // `early` was quarantined at the snapshot, so a normal scope never selected it and has nothing to report.
    expect(normalStatus.omissions).toEqual([{ sourceId: lateHeld, reason: 'quarantined' }]);
    expect(everything(await files(normal.body.data.id))).not.toContain('late-marker-1');

    const quarantineStatus = await status(quarantined.body.data.id);
    expect(await sourcesOf(quarantined.body.data.id)).toEqual([]);
    expect(quarantineStatus.omissions).toEqual([{ sourceId: early, reason: 'admission_changed' }]);
    expect(everything(await files(quarantined.body.data.id))).not.toContain('early-marker-2');
  });

  it('checks a quarantine again on download and marks the export invalid until the source is released', async () => {
    const a = await ingest(mail({ subject: 'Invoice', body: 'download-marker' }));
    const { id } = await built({});
    const whole = await built({ scope: { kind: 'archive', admission: 'all' } });
    await quarantine(a);
    expect(await status(id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'quarantined', counts: null, omissions: null });
    const blocked = await api('GET', `/exports/${id}/content`, { token });
    expect(blocked.status).toBe(410);
    expect(blocked.body.error?.code).toBe('quarantined');
    // The export that asked for quarantine as well is unaffected.
    expect((await api('GET', `/exports/${whole.id}/content`, { token })).status).toBe(200);
    // A read never rewrites the export: releasing the source makes the same normal export readable again.
    await release(a);
    expect(await status(id)).toMatchObject({ state: 'ready', invalidatedReason: null });
    expect((await api('GET', `/exports/${id}/content`, { token })).status).toBe(200);
  });

  it('checks admission both ways: a quarantine-only export is invalid once its source is released, ready or not', async () => {
    const a = await ingest(mail({ subject: 'Held mail', body: 'released-marker' }));
    await quarantine(a);
    const only = await built({ scope: { kind: 'archive', admission: 'quarantine' } });
    const all = await built({ scope: { kind: 'archive', admission: 'all' } });
    expect(await sourcesOf(only.id)).toEqual([a]);
    expect((await api('GET', `/exports/${only.id}/content`, { token })).status).toBe(200);

    // Released after the export became ready: the quarantine-only export no longer matches what it was asked for.
    await release(a);
    expect(await status(only.id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'admission_changed', counts: null, omissions: null });
    const blocked = await api('GET', `/exports/${only.id}/content`, { token });
    expect(blocked.status).toBe(410);
    expect(blocked.body.error?.code).toBe('admission_changed');
    // An export that accepted both is unaffected, and a quarantine-only one is restored when the source is held again.
    expect((await api('GET', `/exports/${all.id}/content`, { token })).status).toBe(200);
    expect(await sourcesOf(all.id)).toContain(a);
    await quarantine(a);
    expect(await status(only.id)).toMatchObject({ state: 'ready', invalidatedReason: null });
    expect((await api('GET', `/exports/${only.id}/content`, { token })).status).toBe(200);
  });

  it('does not become ready when a source of a quarantine-only export is released while the ZIP is being written', async () => {
    const a = await ingest(mail({ subject: 'Held mail', body: 'released-while-writing' }));
    await quarantine(a);
    const created = await create({ scope: { kind: 'archive', admission: 'quarantine' } });
    const racing = new Proxy(env.ARCHIVE_BUCKET, {
      get(target, prop) {
        const member = Reflect.get(target, prop) as unknown;
        if (prop !== 'put') return typeof member === 'function' ? member.bind(target) : member;
        return async (objectKey: string, ...rest: unknown[]) => {
          if (objectKey.endsWith('manifest.json')) await env.CONTROL_DB.prepare("UPDATE source_registry SET admission = 'normal' WHERE id = ?").bind(a).run();
          return (target.put as (...args: unknown[]) => Promise<unknown>)(objectKey, ...rest);
        };
      },
    }) as R2Bucket;
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: racing })).toEqual(['complete']);
    expect(await status(created.body.data.id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'admission_changed' });
    expect((await api('GET', `/exports/${created.body.data.id}/content`, { token })).status).toBe(410);
    expect((await expireExports(env, T0)).removed).toBe(1);
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${created.body.data.id}/` })).objects).toEqual([]);
  });

  it('does not become ready when a source is quarantined while the ZIP is being written', async () => {
    const a = await ingest(mail({ subject: 'Invoice', body: 'racing-marker' }));
    const created = await create({});
    const racing = new Proxy(env.ARCHIVE_BUCKET, {
      get(target, prop) {
        const member = Reflect.get(target, prop) as unknown;
        if (prop !== 'put') return typeof member === 'function' ? member.bind(target) : member;
        // The manifest is the last object written; the quarantine lands between the build and the commit.
        return async (objectKey: string, ...rest: unknown[]) => {
          if (objectKey.endsWith('manifest.json')) await env.CONTROL_DB.prepare("UPDATE source_registry SET admission = 'quarantine' WHERE id = ?").bind(a).run();
          return (target.put as (...args: unknown[]) => Promise<unknown>)(objectKey, ...rest);
        };
      },
    }) as R2Bucket;
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: racing })).toEqual(['complete']);
    expect(await status(created.body.data.id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'quarantined' });
    expect((await api('GET', `/exports/${created.body.data.id}/content`, { token })).status).toBe(410);
    // The invalid export's objects are removed with the scheduled cleanup.
    expect((await expireExports(env, T0)).removed).toBe(1);
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${created.body.data.id}/` })).objects).toEqual([]);
  });
});

describe('export scope: filtered subsets', () => {
  it('selects by sender, recipient and received time with the same bounds as search', async () => {
    const a = await ingest(mail({ subject: 'A', from: 'Billing@Vendor.example' }), { now: T0 });
    const b = await ingest(mail({ subject: 'B', from: 'other@example.net', to: 'team@adminifender.example' }), { now: T0 + MIN });
    const c = await ingest(mail({ subject: 'C', from: 'billing@vendor.example' }), { now: T0 + 2 * MIN });
    const at = (ms: number) => new Date(ms).toISOString();

    expect((await built({ scope: { kind: 'filter', filter: { sender: 'BILLING@vendor.example' } } })).created.scope).toEqual({ kind: 'filter', admission: 'normal', filter: { sender: 'billing@vendor.example' } });
    const sender = await built({ scope: { kind: 'filter', filter: { sender: 'billing@vendor.example' } } });
    expect((await sourcesOf(sender.id)).sort()).toEqual([a, c].sort());
    const recipient = await built({ scope: { kind: 'filter', filter: { recipient: 'team@adminifender.example' } } });
    expect(await sourcesOf(recipient.id)).toEqual([b]);
    // `from` includes its instant and `to` excludes it, exactly as GET /search does.
    const range = await built({ scope: { kind: 'filter', filter: { from: at(T0 + MIN), to: at(T0 + 2 * MIN) } } });
    expect(await sourcesOf(range.id)).toEqual([b]);
    const both = await built({ scope: { kind: 'filter', filter: { from: at(T0), sender: 'billing@vendor.example', to: at(T0 + 2 * MIN) } } });
    expect(await sourcesOf(both.id)).toEqual([a]);
    const none = await built({ scope: { kind: 'filter', filter: { sender: 'nobody@example.org' } } });
    expect(none.status).toMatchObject({ state: 'ready', counts: { sources: 0 }, omissions: [] });
  });

  it('reads the whole selection, not a first page', async () => {
    const ids: string[] = [];
    for (let i = 0; i < 52; i += 1) ids.push(await ingest(mail({ subject: `Bulk ${i}`, from: i % 2 ? 'odd@example.net' : 'even@example.net' }), { now: T0 + i }));
    const all = await built({});
    expect((await sourcesOf(all.id)).length).toBe(52);
    const evens = await built({ scope: { kind: 'filter', filter: { sender: 'even@example.net' } } });
    expect((await sourcesOf(evens.id)).sort()).toEqual(ids.filter((_, i) => i % 2 === 0).sort());
    // And the same through explicit IDs, beyond one query's variable limit.
    const named = await built({ scope: { kind: 'sources', sourceIds: ids } });
    expect((await sourcesOf(named.id)).length).toBe(52);
    expect(named.status.omissions).toEqual([]);
  }, 120_000);

  it('selects explicit sources, accounts for every one it cannot include and checks admission', async () => {
    const a = await ingest(mail({ subject: 'A' }));
    const b = await ingest(mail({ subject: 'B' }), { now: T0 + 1 });
    const held = await ingest(mail({ subject: 'Held', body: 'named-quarantine-marker' }), { now: T0 + 2 });
    await quarantine(held);
    const unknown = crypto.randomUUID();

    const named = await built({ scope: { kind: 'sources', sourceIds: [held, a, unknown] } });
    expect(named.created.scope).toEqual({ kind: 'sources', admission: 'normal', sourceIds: [a, held, unknown].sort() });
    expect(await sourcesOf(named.id)).toEqual([a]);
    expect(named.status.omissions).toEqual(expect.arrayContaining([{ sourceId: held, reason: 'excluded_by_admission' }, { sourceId: unknown, reason: 'not_in_snapshot' }]));
    expect(named.status.omissions).toHaveLength(2);
    expect(everything(await files(named.id))).not.toContain('named-quarantine-marker');

    const asked = await built({ scope: { kind: 'sources', admission: 'all', sourceIds: [held, b] } });
    expect((await sourcesOf(asked.id)).sort()).toEqual([b, held].sort());
    expect(asked.status.omissions).toEqual([]);
  });

  it('keeps only selected links in concerns and never lets an unselected source speak through a shared concern', async () => {
    const secret = await ingest(mail({ subject: 'QUARANTINED-SUBJECT-5519', body: 'quarantined-body-5519', id: '<q@vendor.example>' }));
    await runPending('triage');
    const visible = await ingest(mail({ subject: 'Visible follow up', body: 'follow up', id: '<v@vendor.example>', inReplyTo: '<q@vendor.example>' }), { now: T0 + MIN });
    await runPending('triage', T0 + MIN);
    const concernId = (await env.CONTROL_DB.prepare('SELECT concern_id FROM concern_sources WHERE source_id = ?').bind(visible).first<{ concern_id: string }>())!.concern_id;
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM concern_sources WHERE concern_id = ?').bind(concernId).first<{ n: number }>())!.n).toBe(2);
    // Owner-typed wording can quote the mail it was written from, so none of it is trusted on a partial concern.
    const revision = async () => (await api('GET', `/concerns/${concernId}`, { token })).body.data.concern.revision as number;
    const act = async (kind: string, args: unknown) => {
      const res = await api('POST', `/concerns/${concernId}/actions`, { token, key: key(), now: T0 + 2 * MIN, body: { expectedRevision: await revision(), kind, args } });
      expect(res.status).toBe(200);
    };
    await act('set_due', { at: new Date(T0 + 9 * 24 * 60 * MIN).toISOString(), zone: 'UTC', sourceText: 'pay QUARANTINED-DUE-5519 by Friday' });
    await act('wait', { note: 'told QUARANTINED-NOTE-5519 to resend', awaitedParty: 'QUARANTINED-PARTY-5519 Ltd', followUpAt: new Date(T0 + 3 * 24 * 60 * MIN).toISOString() });
    const corrected = await api('POST', `/concerns/${concernId}/corrections`, { token, key: key(), now: T0 + 2 * MIN, body: { expectedRevision: await revision(), importance: 'critical', applyTo: 'this_concern' } });
    expect(corrected.status).toBe(200);
    await quarantine(secret);

    const normal = await built({}, T0 + 3 * MIN);
    const zip = await files(normal.id, T0 + 3 * MIN);
    expect(everything(zip)).not.toContain('5519');
    const [concern] = lines(zip.get('concerns.jsonl'));
    // Structured facts are as untrusted as wording: nothing in a concern says which email a date, status or importance came
    // from, so the partial record carries none of them (the owner's own read and acknowledge times stay).
    const dueAt = new Date(T0 + 9 * 24 * 60 * MIN).toISOString();
    const followUpAt = new Date(T0 + 3 * 24 * 60 * MIN).toISOString();
    expect(everything(zip)).not.toContain(dueAt);
    expect(everything(zip)).not.toContain(followUpAt);
    expect(concern).toEqual({
      id: concernId,
      revision: expect.any(Number),
      title: 'Visible follow up',
      status: null,
      importance: null,
      criticalBasis: null,
      serviceId: null,
      readAt: null,
      acknowledgedAt: null,
      resolvedAt: null,
      snoozeUntil: null,
      waiting: null,
      due: null,
      sourceIds: [visible],
      updatedAt: null,
      partial: true,
      withheld: ['title', 'status', 'importance', 'criticalBasis', 'serviceId', 'resolvedAt', 'snoozeUntil', 'waiting', 'due', 'updatedAt'],
    });
    // The key set is the same as a full concern's, so a reader never meets a missing key.
    expect(JSON.parse(decoder.decode(zip.get('manifest.json'))).sections.partialConcerns).toBe(1);

    const whole = await built({ scope: { kind: 'archive', admission: 'all' } }, T0 + 3 * MIN);
    const [full] = lines((await files(whole.id, T0 + 3 * MIN)).get('concerns.jsonl'));
    expect(full).toMatchObject({ id: concernId, partial: false, withheld: [], criticalBasis: 'owner', waiting: { note: 'told QUARANTINED-NOTE-5519 to resend', awaitedParty: 'QUARANTINED-PARTY-5519 Ltd' } });
    expect([...full.sourceIds].sort()).toEqual([secret, visible].sort());
    expect(Object.keys(full).sort()).toEqual(Object.keys(concern).sort());
    expect(full).toMatchObject({ status: 'waiting', importance: 'critical', due: { at: dueAt }, waiting: { followUpAt } });
  });

  it('writes the owner rules and services only for a whole-archive scope', async () => {
    await ingest(mail({ subject: 'Invoice' }));
    await api('POST', '/rules', { token, key: key(), body: { predicate: { domain_equals: 'vendor.example' }, effects: [{ surface: true }] } });
    const whole = await built({});
    expect(whole.status.counts).toMatchObject({ rules: 1 });
    const subset = await built({ scope: { kind: 'filter', filter: { sender: 'ops@vendor.example' } } });
    expect(subset.status.counts).toMatchObject({ sources: 1, rules: 0, services: 0 });
    const zip = await files(subset.id);
    expect(decoder.decode(zip.get('rules.jsonl'))).toBe('');
    expect(JSON.parse(decoder.decode(zip.get('manifest.json'))).sections).toMatchObject({ rules: 'omitted_by_scope', services: 'omitted_by_scope' });
  });
});

describe('export scope: deletion wins', () => {
  it('invalidates only the exports that contain a deleted source', async () => {
    const a = await ingest(mail({ subject: 'A' }));
    const b = await ingest(mail({ subject: 'B' }), { now: T0 + 1 });
    const withA = await built({});
    const onlyB = await built({ scope: { kind: 'sources', sourceIds: [b] } });
    await deleteSources([a]);
    expect(await status(withA.id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'deleted' });
    const gone = await api('GET', `/exports/${withA.id}/content`, { token });
    expect(gone.status).toBe(410);
    expect(gone.body.error?.code).toBe('deleted');
    expect(await status(onlyB.id)).toMatchObject({ state: 'ready' });
    expect((await api('GET', `/exports/${onlyB.id}/content`, { token })).status).toBe(200);
  });

  it('lists a selected source deleted between the snapshot and the build as omitted, whatever the scope', async () => {
    const a = await ingest(mail({ subject: 'A', body: 'deleted-marker' }));
    const created = await create({ scope: { kind: 'sources', sourceIds: [a] } });
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleting' WHERE id = ?").bind(a).run();
    await runPending('export');
    const state = await status(created.body.data.id);
    expect(state).toMatchObject({ state: 'ready', counts: { sources: 0 }, omissions: [{ sourceId: a, reason: 'deleted' }] });
    expect(everything(await files(created.body.data.id))).not.toContain('deleted-marker');
  });

  it('never selects a source whose deletion was already recorded at the snapshot', async () => {
    const a = await ingest(mail({ subject: 'A' }));
    const b = await ingest(mail({ subject: 'B' }), { now: T0 + 1 });
    await deleteSources([a]);
    await runPending('cleanup');
    const named = await built({ scope: { kind: 'sources', sourceIds: [a, b] } });
    expect(await sourcesOf(named.id)).toEqual([b]);
    expect(named.status.omissions).toEqual([{ sourceId: a, reason: 'not_in_snapshot' }]);
  });
});

describe('export scope: idempotency and validation', () => {
  it('binds the Idempotency-Key to the normalized scope', async () => {
    const k = key();
    const first = await create({}, { key: k });
    const sameMeaning = await create({ scope: { kind: 'archive', admission: 'normal' } }, { key: k });
    expect(sameMeaning.status).toBe(200);
    expect(sameMeaning.body.data).toEqual(first.body.data);
    const widened = await create({ scope: { kind: 'archive', admission: 'all' } }, { key: k });
    expect(widened.status).toBe(409);
    expect(widened.body.error?.code).toBe('idempotency_conflict');
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM exports').first<{ n: number }>())!.n).toBe(1);
    expect((await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE kind = 'export'").first<{ n: number }>())!.n).toBe(1);

    const ids = [crypto.randomUUID(), crypto.randomUUID()];
    const named = key();
    const one = await create({ scope: { kind: 'sources', sourceIds: ids } }, { key: named });
    const reordered = await create({ scope: { kind: 'sources', sourceIds: [...ids].reverse() } }, { key: named });
    expect(reordered.body.data.id).toBe(one.body.data.id);
    const other = await create({ scope: { kind: 'sources', sourceIds: [ids[0]] } }, { key: named });
    expect(other.status).toBe(409);
  });

  it('refuses a request that an earlier release would have read as a different, broader export', async () => {
    // The first release hashed an empty body for this key; the same key now carries a normalized scope.
    const k = key();
    const legacyHash = (await import('../../src/ids')).sha256Hex(`POST\n/api/v1/exports\n{}`);
    await env.CONTROL_DB.prepare("INSERT INTO mutations(key, request_hash, state, result_json, created_at, expires_at) VALUES (?, ?, 'complete', ?, ?, ?)")
      .bind(k, await legacyHash, JSON.stringify({ status: 200, body: { data: { id: crypto.randomUUID(), state: 'pending' } } }), T0 - MIN, T0 + 90 * 24 * 60 * MIN)
      .run();
    const retried = await create({}, { key: k });
    expect(retried.status).toBe(409);
    expect(retried.body.error?.code).toBe('idempotency_conflict');
  });

  it('rejects malformed scopes before anything is stored', async () => {
    const bad: unknown[] = [
      { scope: { kind: 'archive', admission: 'include_quarantine' } },
      { scope: { kind: 'archive', includeQuarantine: true } },
      { scope: { kind: 'filter' } },
      { scope: { kind: 'filter', filter: {} } },
      { scope: { kind: 'filter', filter: { from: '2026-10-02T00:00:00Z', to: '2026-10-01T00:00:00Z' } } },
      { scope: { kind: 'filter', filter: { from: '2026-10-01T00:00:00Z', to: '2026-10-01T00:00:00Z' } } },
      { scope: { kind: 'filter', filter: { sender: '   ' } } },
      { scope: { kind: 'filter', filter: { subject: 'invoice' } } },
      { scope: { kind: 'sources', sourceIds: [] } },
      { scope: { kind: 'sources', sourceIds: [crypto.randomUUID(), 'not-a-uuid'] } },
      { scope: { kind: 'sources', sourceIds: Array.from({ length: 1001 }, () => crypto.randomUUID()) } },
      { scope: { kind: 'sources', sourceIds: ['3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b', '3f9a1c2e-5b7d-4e8f-9a0b-1c2d3e4f5a6b'] } },
      { scope: { kind: 'sources', sourceIds: [crypto.randomUUID()], filter: { sender: 'a@b.example' } } },
      { scope: { kind: 'everything' } },
      { scope: 'all' },
      { includeQuarantine: true },
    ];
    for (const body of bad) {
      const res = await create(body);
      expect(res.status, JSON.stringify(body).slice(0, 120)).toBe(400);
      expect(res.body.error?.code).toBe('invalid_request');
    }
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM exports').first<{ n: number }>())!.n).toBe(0);
    const biggest = await create({ scope: { kind: 'sources', sourceIds: Array.from({ length: 1000 }, () => crypto.randomUUID()) } });
    expect(biggest.status).toBe(200);
  });
});

describe('export scope: older exports fail closed', () => {
  const legacy = async (state: string, scopeJson: string | null, objectKey: string | null = null) => {
    const id = crypto.randomUUID();
    await env.CONTROL_DB.prepare('INSERT INTO exports(id, snapshot_seq, state, object_key, manifest_key, created_at, expires_at, scope_json) VALUES (?, 0, ?, ?, ?, ?, ?, ?)')
      .bind(id, state, objectKey, objectKey ? `${objectKey}.manifest` : null, T0, T0 + 7 * 24 * 60 * MIN, scopeJson)
      .run();
    return id;
  };

  it('treats an export with no recorded scope as invalid, never as the whole archive', async () => {
    await env.ARCHIVE_BUCKET.put('exports/legacy/export.zip', 'zip bytes that may include quarantine');
    const ready = await legacy('ready', null, 'exports/legacy/export.zip');
    expect(await status(ready)).toMatchObject({ state: 'invalidated', scope: null, invalidatedReason: 'scope_unknown', counts: null });
    const blocked = await api('GET', `/exports/${ready}/content`, { token });
    expect(blocked.status).toBe(410);
    expect(blocked.body.error?.code).toBe('scope_unknown');
    // Its object does not wait for the seven-day expiry.
    expect(await expireExports(env, T0 + 1)).toEqual({ removed: 1 });
    expect(await env.ARCHIVE_BUCKET.get('exports/legacy/export.zip')).toBeNull();
    expect(await status(ready)).toMatchObject({ state: 'invalidated', invalidatedReason: 'scope_unknown' });
  });

  it('does not build a pending export without a readable scope', async () => {
    await ingest(mail({ subject: 'Held', body: 'legacy-marker' }));
    const missing = await legacy('pending', null);
    const garbled = await legacy('pending', '{"kind":"archive"');
    const widened = await legacy('pending', '{"kind":"archive","admission":"normal","extra":true}');
    for (const id of [missing, garbled, widened]) {
      await env.CONTROL_DB.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES (?, ?, 'export', 'pending', ?, ?, ?)")
        .bind(crypto.randomUUID(), `export:${id}`, T0, JSON.stringify({ exportId: id }), T0)
        .run();
    }
    expect(await runPending('export')).toEqual(['failed', 'failed', 'failed']);
    for (const id of [missing, garbled, widened]) {
      expect(await status(id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'scope_unknown' });
      expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects).toEqual([]);
      expect((await api('GET', `/exports/${id}/content`, { token })).status).toBe(410);
    }
  });

  it('reports a job that gave up as failed with its fixed code', async () => {
    const created = await create({});
    expect((await status(created.body.data.id)).state).toBe('pending');
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'dead', error_code = 'attachment_unavailable' WHERE dedupe_key = ?").bind(`export:${created.body.data.id}`).run();
    const failed = await status(created.body.data.id);
    expect(failed).toMatchObject({ state: 'failed', failureCode: 'attachment_unavailable', counts: null });
    expect((await api('GET', `/exports/${created.body.data.id}/content`, { token })).body.error?.code).toBe('export_failed');
  });
});

describe('export contract', () => {
  it('rejects the shapes the schemas forbid', async () => {
    const created = await create({});
    expectContractViolation('export-status', { ...created.body.data, state: 'ready' });
    expectContractViolation('export-status', { ...created.body.data, objectKey: 'exports/x/export.zip' });
    expectContractViolation('export-status', { ...created.body.data, scope: { kind: 'archive' } });
    expect(await runPending('export')).toEqual(['complete']);
    const ready = await status(created.body.data.id);
    expectContractViolation('export-status', { ...ready, counts: null });
    expectContractViolation('export-status', { ...ready, omissions: [{ sourceId: crypto.randomUUID(), reason: 'because' }] });
  });
});
