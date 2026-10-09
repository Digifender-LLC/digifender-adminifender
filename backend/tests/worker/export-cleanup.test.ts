import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { archiveDeletionStatements, planArchiveDeletion } from '../../src/deletion/archive';
import { EXPORT_RECONCILE_MS, STALE_UPLOAD_GRACE_MS, exportJob, expireExports } from '../../src/exports/job';
import { inflateEntry, readCentralDirectory } from '../../src/extraction/zip';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { LEASE_MS, claimJob, runJob } from '../../src/jobs/runner';
import { T0, api, enrollOwner, ingest, quietEnv, runPending, sessionClock } from './support';

const MIN = 60_000;
const GRACE = STALE_UPLOAD_GRACE_MS;
const RECONCILE = EXPORT_RECONCILE_MS;
const RETRY = 15 * MIN;
let token: string;
let clock: (now: number) => Promise<string>;
let keyCount = 0;
const key = () => `00000000-0000-4000-b100-${String((keyCount += 1)).padStart(12, '0')}`;
const decoder = new TextDecoder();

const mail = (subject: string, body: string) =>
  ['From: ops@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, '', body, ''].join('\r\n');

async function unzip(bytes: Uint8Array): Promise<Map<string, Uint8Array>> {
  const out = new Map<string, Uint8Array>();
  for (const entry of readCentralDirectory(bytes)) out.set(entry.name, await inflateEntry(bytes, entry, 20 * 1024 * 1024));
  return out;
}
const lines = (data: Uint8Array | undefined) => decoder.decode(data).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));
const create = async (body: unknown = {}, now = T0) => {
  const res = await api('POST', '/exports', { token, key: key(), body, now });
  expect(res.status).toBe(200);
  return res.body.data.id as string;
};
const status = async (id: string, now = T0) => (await api('GET', `/exports/${id}`, { token, now })).body.data;
const download = async (id: string, now = T0) => api('GET', `/exports/${id}/content`, { token, now });
/** The same reads far from T0, with an access token refreshed for that time (calls must move forward in time). */
const statusAt = async (id: string, now: number) => (await api('GET', `/exports/${id}`, { token: await clock(now), now })).body.data;
const downloadAt = async (id: string, now: number) => api('GET', `/exports/${id}/content`, { token: await clock(now), now });
const keysOf = async (id: string) => (await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects.map((o) => o.key).sort();
const jobOf = async (id: string) => (await env.CONTROL_DB.prepare('SELECT id FROM jobs WHERE dedupe_key = ?').bind(`export:${id}`).first<{ id: string }>())!.id;
const row = (id: string) => env.CONTROL_DB.prepare('SELECT * FROM exports WHERE id = ?').bind(id).first<Record<string, any>>();
async function quarantine(sourceId: string) {
  const revision = (await api('GET', `/sources/${sourceId}`, { token })).body.data.source.revision;
  expect((await api('POST', `/sources/${sourceId}/quarantine`, { token, key: key(), body: { expectedRevision: revision } })).status).toBe(200);
}
/** The same control transaction as a confirmed archive deletion (planned and applied by the real functions). */
async function deleteForReal(sourceId: string, now = T0) {
  const quiet = quietEnv();
  const plan = await planArchiveDeletion(quiet, [sourceId]);
  await env.CONTROL_DB.batch(await archiveDeletionStatements(quiet, plan, crypto.randomUUID(), now));
}
/** A bucket that runs hooks before a put or a delete and otherwise behaves like the real one. */
function bucketWith(hooks: {
  put?: (objectKey: string) => Promise<void> | void;
  delete?: (keys: string | string[]) => Promise<void> | void;
  list?: (options: R2ListOptions | undefined) => Promise<void> | void;
}): R2Bucket {
  return new Proxy(env.ARCHIVE_BUCKET, {
    get(target, prop) {
      const member = Reflect.get(target, prop) as unknown;
      if (prop === 'list' && hooks.list) {
        return async (options?: R2ListOptions) => {
          await hooks.list?.(options);
          return target.list(options);
        };
      }
      if (prop === 'put' && hooks.put) {
        return async (objectKey: string, ...rest: unknown[]) => {
          await hooks.put?.(objectKey);
          return (target.put as (...args: unknown[]) => Promise<unknown>)(objectKey, ...rest);
        };
      }
      if (prop === 'delete' && hooks.delete) {
        return async (keys: string | string[]) => {
          await hooks.delete?.(keys);
          return target.delete(keys);
        };
      }
      return typeof member === 'function' ? member.bind(target) : member;
    },
  }) as R2Bucket;
}
const failing = () => {
  throw new Error('injected R2 failure');
};

beforeEach(async () => {
  const owner = await enrollOwner();
  token = owner.token;
  clock = sessionClock(owner);
});

describe('export objects are never kept for an export that cannot be downloaded', () => {
  it('removes a ZIP whose export was invalidated by a real deletion while it was being built, and a late upload after the sweep', async () => {
    const a = await ingest(mail('Invoice', 'deleted-while-building'));
    await ingest(mail('Other', 'kept words'), { now: T0 + 1 });
    const id = await create();
    // The attempt's own cleanup fails as well, so only the sweep can remove what it wrote.
    const racing = bucketWith({ put: (objectKey) => (objectKey.endsWith('manifest.json') ? deleteForReal(a) : undefined), delete: failing });
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: racing })).toEqual(['complete']);
    expect(await status(id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'deleted' });
    expect((await download(id)).status).toBe(410);
    // The ZIP and manifest exist, but no key was ever recorded for them.
    expect(await keysOf(id)).toHaveLength(2);
    expect(await row(id)).toMatchObject({ object_key: null, manifest_key: null });

    expect((await expireExports(env, T0 + MIN)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
    // An upload that was in flight lands after that sweep: it is still removed once the stale-upload grace has passed.
    await env.ARCHIVE_BUCKET.put(`exports/${id}/${crypto.randomUUID()}/export.zip`, 'late zip bytes');
    expect((await expireExports(env, T0 + 2 * MIN)).removed).toBe(0);
    expect(await keysOf(id)).toHaveLength(1);
    expect((await expireExports(env, T0 + GRACE)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
    // The export is not finished: it is visited again at the reconcile interval, however late an R2 request completes.
    expect(await row(id)).toMatchObject({ cleanup_next_at: T0 + GRACE + RECONCILE });
    await env.ARCHIVE_BUCKET.put(`exports/${id}/after-final/export.zip`, 'lands after the final grace sweep');
    expect((await expireExports(env, T0 + GRACE + 5 * MIN)).removed).toBe(0);
    expect(await keysOf(id)).toEqual([`exports/${id}/after-final/export.zip`]);
    expect((await expireExports(env, T0 + GRACE + RECONCILE - 1)).removed).toBe(0);
    expect((await expireExports(env, T0 + GRACE + RECONCILE)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'invalidated', cleanup_next_at: T0 + GRACE + 2 * RECONCILE });
    // And again, with no end: a second late object is removed at the next reconciliation.
    await env.ARCHIVE_BUCKET.put(`exports/${id}/much-later/export.zip`, 'lands days later');
    expect((await expireExports(env, T0 + 3 * 24 * 60 * MIN)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
  });

  it('removes its own objects at once when it finds the export invalidated before it can publish', async () => {
    const a = await ingest(mail('Invoice', 'deleted-while-building'));
    const id = await create();
    const racing = bucketWith({ put: (objectKey) => (objectKey.endsWith('manifest.json') ? deleteForReal(a) : undefined) });
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: racing })).toEqual(['complete']);
    expect(await status(id)).toMatchObject({ state: 'invalidated', invalidatedReason: 'deleted' });
    expect(await keysOf(id)).toEqual([]);
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM export_sources WHERE export_id = ?').bind(id).first<{ n: number }>())!.n).toBe(0);
  });

  it('removes the attempt objects when the upload succeeded and then a failure, and a deletion, left no key recorded', async () => {
    const a = await ingest(mail('Invoice', 'failed-after-upload'));
    const id = await create();
    // The manifest write fails after the ZIP is complete, and the attempt's own cleanup fails too.
    const broken = bucketWith({
      put: (objectKey) => (objectKey.endsWith('manifest.json') ? failing() : undefined),
      delete: failing,
    });
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: broken })).toEqual(['retry']);
    expect((await keysOf(id)).length).toBeGreaterThan(0);
    expect(await row(id)).toMatchObject({ state: 'running', object_key: null });
    // The owner deletes mail meanwhile: the export is invalidated, so the retry has nothing to do.
    await deleteForReal(a, T0 + 10_000);
    expect(await runPending('export', T0 + 60_000)).toEqual(['complete']);
    expect(await status(id, T0 + 60_000)).toMatchObject({ state: 'invalidated', invalidatedReason: 'deleted' });
    expect((await keysOf(id)).length).toBeGreaterThan(0);
    expect((await expireExports(env, T0 + 61_000)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
  });

  it('removes the attempt objects when the final transaction never committed and the export was invalidated afterwards', async () => {
    const a = await ingest(mail('Invoice', 'never-committed'));
    const id = await create();
    // The handler finished and its objects exist; the settling batch is lost (the worker died or D1 failed).
    const job = (await claimJob(env.CONTROL_DB, await jobOf(id), T0))!;
    const built = await exportJob(job, quietEnv(), T0);
    expect(built.kind).toBe('complete');
    expect(await keysOf(id)).toHaveLength(2);
    await deleteForReal(a, T0 + 1000);
    // The lease expires, the job is claimed again and finds the export invalidated.
    expect(await runJob(quietEnv(), JOB_HANDLERS, job.id, T0 + LEASE_MS + 1)).toEqual({ jobId: job.id, status: 'complete' });
    expect(await status(id)).toMatchObject({ state: 'invalidated' });
    expect((await expireExports(env, T0 + LEASE_MS + 2)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
  });

  it('removes what a failed or abandoned build left, once its job can no longer run, and never touches a build in progress', async () => {
    await ingest(mail('Invoice', 'abandoned'));
    const running = await create();
    const dead = await create();
    for (const id of [running, dead]) await env.ARCHIVE_BUCKET.put(`exports/${id}/${crypto.randomUUID()}/export.zip`, 'partial attempt');
    // A job that is still claimable (pending, or running under a lease) may be about to publish: its objects stay.
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'running', lease_token = 'held', lease_until = ? WHERE dedupe_key = ?").bind(T0 + LEASE_MS, `export:${running}`).run();
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'dead', error_code = 'attachment_unavailable' WHERE dedupe_key = ?").bind(`export:${dead}`).run();
    expect((await expireExports(env, T0 + MIN)).removed).toBe(1);
    expect(await keysOf(running)).toHaveLength(1);
    expect(await keysOf(dead)).toEqual([]);
    expect(await row(dead)).toMatchObject({ state: 'failed' });
    expect(await status(dead, T0 + MIN)).toMatchObject({ state: 'failed', failureCode: 'attachment_unavailable' });
  });

  it('reads every page of a large prefix, and a failing export never holds up the others', async () => {
    const ids: string[] = [];
    for (let i = 0; i < 60; i += 1) {
      const id = crypto.randomUUID();
      ids.push(id);
      await env.CONTROL_DB.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at, scope_json, invalidated_reason) VALUES (?, 0, 'invalidated', ?, ?, ?, 'deleted')")
        .bind(id, T0 + i, T0 + 7 * 24 * 60 * MIN, '{"admission":"normal","kind":"archive"}')
        .run();
      await env.ARCHIVE_BUCKET.put(`exports/${id}/attempt/export.zip`, `zip ${i}`);
    }
    // More objects under one prefix than a single list or delete call carries.
    for (let i = 0; i < 1005; i += 1) await env.ARCHIVE_BUCKET.put(`exports/${ids[5]}/many/${String(i).padStart(4, '0')}`, 'x');
    const stuck = ids[0]!;
    const flaky = bucketWith({ delete: (keys) => ([keys].flat().some((k) => k.startsWith(`exports/${stuck}/`)) ? failing() : undefined) });

    // The oldest export fails; the pass still handles the other 49 of its batch of 50, and the failure waits.
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: flaky }, T0 + MIN)).removed).toBe(49);
    expect(await row(stuck)).toMatchObject({ cleanup_next_at: expect.any(Number) });
    expect((await row(stuck))!.cleanup_next_at).toBeGreaterThan(T0 + MIN);
    // The next pass takes the remaining 10 and does not retry the failing one yet.
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: flaky }, T0 + 2 * MIN)).removed).toBe(10);
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${stuck}/` })).objects).toHaveLength(1);
    for (const id of ids.slice(1)) expect(await keysOf(id)).toEqual([]);
    // Later the failure clears and it goes too.
    expect((await expireExports(env, T0 + 30 * MIN)).removed).toBe(1);
    expect(await keysOf(stuck)).toEqual([]);
    expect((await env.ARCHIVE_BUCKET.list({ prefix: 'exports/' })).objects).toEqual([]);
  }, 120_000);

  it('reconciles due exports oldest-due first, 50 per pass, listing only each export’s own prefix and nothing that is not due', async () => {
    const closed = (id: string, createdAt: number, settledAt: number, nextAt: number | null) =>
      env.CONTROL_DB.prepare(
        "INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at, scope_json, invalidated_reason, settled_at, cleanup_next_at) VALUES (?, 0, 'invalidated', ?, ?, ?, 'deleted', ?, ?)",
      )
        .bind(id, createdAt, T0 + 7 * 24 * 60 * MIN, '{"admission":"normal","kind":"archive"}', settledAt, nextAt)
        .run();
    const due = T0 + RECONCILE;
    const overdue: string[] = [];
    for (let i = 0; i < 60; i += 1) {
      const id = crypto.randomUUID();
      overdue.push(id);
      // Created in the opposite order to being due, so created_at order would pick different exports.
      await closed(id, T0 + (100 - i), T0, due + i * 1000);
      await env.ARCHIVE_BUCKET.put(`exports/${id}/late/export.zip`, 'landed after the last sweep');
    }
    // Closed since, never visited yet: it is due later than every overdue visit, so it does not jump ahead of them.
    const fresh = crypto.randomUUID();
    await closed(fresh, T0, due + 61_000, null);
    await env.ARCHIVE_BUCKET.put(`exports/${fresh}/late/export.zip`, 'landed after close');
    // Not due: visited recently.
    const resting = crypto.randomUUID();
    await closed(resting, T0, T0, due + 24 * 60 * MIN);
    await env.ARCHIVE_BUCKET.put(`exports/${resting}/late/export.zip`, 'not yet due');

    const listed: string[] = [];
    const counting = { CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: bucketWith({ list: (options) => void listed.push(options?.prefix ?? '(whole bucket)') }) };
    const now = due + 120_000;
    expect((await expireExports(counting, now)).removed).toBe(50);
    expect(listed).toEqual(overdue.slice(0, 50).map((id) => `exports/${id}/`));
    for (const id of overdue.slice(0, 50)) expect(await keysOf(id)).toEqual([]);
    for (const id of [...overdue.slice(50), fresh, resting]) expect(await keysOf(id)).toHaveLength(1);
    // Visited exports are scheduled a full interval ahead and are not listed again by the next pass.
    expect((await row(overdue[0]!))!.cleanup_next_at).toBe(now + RECONCILE);
    listed.length = 0;
    expect((await expireExports(counting, now + MIN)).removed).toBe(11);
    expect(listed.sort()).toEqual([...overdue.slice(50), fresh].map((id) => `exports/${id}/`).sort());
    expect(await keysOf(resting)).toHaveLength(1);
    listed.length = 0;
    expect((await expireExports(counting, now + 2 * MIN)).removed).toBe(0);
    expect(listed).toEqual([]);
  }, 120_000);

  it('retries a reconciliation that fails or hangs later, without holding up the other exports or losing the late object', async () => {
    const ids: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const id = crypto.randomUUID();
      ids.push(id);
      await env.CONTROL_DB.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at, scope_json, invalidated_reason, settled_at, cleanup_next_at) VALUES (?, 0, 'failed', ?, ?, ?, NULL, ?, ?)")
        .bind(id, T0 + i, T0 + 7 * 24 * 60 * MIN, '{"admission":"normal","kind":"archive"}', T0, T0 + RECONCILE)
        .run();
      await env.ARCHIVE_BUCKET.put(`exports/${id}/late/export.zip`, 'late');
    }
    const [stuck, slow, fine] = ids as [string, string, string];
    // `stuck` cannot be listed, `slow` takes a while and then fails to delete; `fine` is untouched by either.
    let slowFailures = 1;
    const broken = bucketWith({
      list: (options) => (options?.prefix === `exports/${stuck}/` ? failing() : undefined),
      delete: async (keys) => {
        if (![keys].flat().some((k) => k.startsWith(`exports/${slow}/`))) return;
        await new Promise((resolve) => setTimeout(resolve, 20));
        if (slowFailures-- > 0) failing();
      },
    });
    const at = T0 + RECONCILE;
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: broken }, at)).removed).toBe(1);
    expect(await keysOf(fine)).toEqual([]);
    expect(await keysOf(stuck)).toHaveLength(1);
    expect(await keysOf(slow)).toHaveLength(1);
    for (const id of [stuck, slow]) expect((await row(id))!.cleanup_next_at).toBe(at + RETRY);
    expect((await row(fine))!.cleanup_next_at).toBe(at + RECONCILE);
    // Not retried before the retry time, whatever the interval; the object is not forgotten.
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: broken }, at + RETRY - 1)).removed).toBe(0);
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: broken }, at + RETRY)).removed).toBe(1);
    expect(await keysOf(slow)).toEqual([]);
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: broken }, at + 2 * RETRY)).removed).toBe(0);
    expect(await keysOf(stuck)).toHaveLength(1);
    expect((await row(stuck))!.cleanup_next_at).toBe(at + 2 * RETRY + RETRY);
    expect((await expireExports(env, at + 3 * RETRY)).removed).toBe(1);
    expect(await keysOf(stuck)).toEqual([]);
    expect((await row(stuck))!.cleanup_next_at).toBe(at + 3 * RETRY + RECONCILE);
  });
});

describe('an attempt that lost its lease cannot change the published export', () => {
  /** Runs attempt A until its manifest write, lets `between` happen, runs B to the end, then lets A finish last. */
  async function overlap(options: { between: () => Promise<void>; staleCleanupFails?: boolean }) {
    const id = await create();
    const jobId = await jobOf(id);
    let reached!: () => void;
    const atManifest = new Promise<void>((resolve) => (reached = resolve));
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const slow = bucketWith({
      put: async (objectKey) => {
        if (!objectKey.endsWith('manifest.json')) return;
        reached();
        await gate;
      },
      ...(options.staleCleanupFails ? { delete: failing } : {}),
    });
    const older = runJob({ ...quietEnv(), ARCHIVE_BUCKET: slow }, JOB_HANDLERS, jobId, T0);
    await atManifest;
    await options.between();
    const newerAt = T0 + LEASE_MS + 1;
    expect(await runJob(quietEnv(), JOB_HANDLERS, jobId, newerAt)).toEqual({ jobId, status: 'complete' });
    const winner = await row(id);
    const winnerBytes = (await download(id, newerAt)).body.data as Uint8Array;
    release();
    expect(await older).toEqual({ jobId, status: 'stale' });
    return { id, newerAt, winner: winner!, winnerBytes };
  }

  it('leaves the newer attempt’s ZIP, manifest and membership in place when the older one finishes last, and never leaks a quarantined source', async () => {
    const a = await ingest(mail('Invoice', 'ordinary words'));
    const secret = await ingest(mail('Secret', 'quarantined-in-overlap-9917'), { now: T0 + 1 });
    const { id, newerAt, winner, winnerBytes } = await overlap({
      between: async () => {
        await quarantine(secret);
        // What a stale attempt A would have left behind, had it written membership for the source it read.
        const created = (await env.CONTROL_DB.prepare('SELECT id FROM exports').first<{ id: string }>())!;
        await env.CONTROL_DB.prepare("INSERT INTO export_sources(export_id, attempt, source_id) VALUES (?, 'stale-attempt', ?)").bind(created.id, secret).run();
      },
    });
    expect(winner).toMatchObject({ state: 'ready' });
    const status1 = await status(id, newerAt);
    expect(status1).toMatchObject({ state: 'ready', counts: { sources: 1 }, omissions: [{ sourceId: secret, reason: 'quarantined' }] });
    // The bytes served after the older attempt finished are the newer attempt's.
    const after = (await download(id, newerAt)).body.data as Uint8Array;
    expect(Array.from(after)).toEqual(Array.from(winnerBytes));
    const files = await unzip(after);
    expect(lines(files.get('sources.jsonl')).map((s: { id: string }) => s.id)).toEqual([a]);
    expect([...files.values()].map((f) => decoder.decode(f)).join('\n')).not.toContain('quarantined-in-overlap-9917');
    expect(JSON.parse(decoder.decode(files.get('manifest.json'))).omissions).toEqual([{ sourceId: secret, reason: 'quarantined' }]);
    // Published keys and membership belong to one attempt only.
    expect(winner.object_key).toBe(`exports/${id}/${winner.attempt}/export.zip`);
    const members = (await env.CONTROL_DB.prepare('SELECT attempt, source_id FROM export_sources WHERE export_id = ?').bind(id).all<{ attempt: string; source_id: string }>()).results;
    expect(members).toEqual([{ attempt: winner.attempt, source_id: a }]);
    expect(await keysOf(id)).toEqual([winner.object_key, winner.manifest_key].sort());
    // Deleting the quarantined source does not touch an export that never contained it.
    await deleteForReal(secret, newerAt + 1000);
    expect(await status(id, newerAt + 2000)).toMatchObject({ state: 'ready' });
    expect((await download(id, newerAt + 2000)).status).toBe(200);
  });

  it('sweeps the older attempt’s orphan objects later and never the winner’s', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const { id, newerAt, winner, winnerBytes } = await overlap({ between: async () => undefined, staleCleanupFails: true });
    const orphans = (await keysOf(id)).filter((k) => !k.startsWith(`exports/${id}/${winner.attempt}/`));
    expect(orphans.length).toBeGreaterThan(0);
    expect(await keysOf(id)).toHaveLength(2 + orphans.length);
    // Within the grace a ready export is not visited at all.
    expect((await expireExports(env, newerAt + 10 * MIN)).removed).toBe(0);
    expect(await keysOf(id)).toHaveLength(2 + orphans.length);
    // After it, only the superseded attempt’s objects go.
    expect((await expireExports(env, newerAt + GRACE)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([winner.object_key, winner.manifest_key].sort());
    expect(Array.from((await download(id, newerAt)).body.data as Uint8Array)).toEqual(Array.from(winnerBytes));
    expect(await row(id)).toMatchObject({ state: 'ready', object_key: winner.object_key });
    expect((await expireExports(env, newerAt + GRACE + MIN)).removed).toBe(0);
    // When it expires everything goes.
    expect((await expireExports(env, newerAt + 8 * 24 * 60 * MIN)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'expired', object_key: null, manifest_key: null });
  });

  it('removes an attempt object that lands after the last grace sweep of a ready export at the next reconciliation, keeping the winning pair', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const { id, newerAt, winner, winnerBytes } = await overlap({ between: async () => undefined, staleCleanupFails: true });
    const published = [winner.object_key, winner.manifest_key].sort();
    const sweptAt = newerAt + GRACE;
    expect((await expireExports(env, sweptAt)).removed).toBe(1);
    expect(await keysOf(id)).toEqual(published);
    expect((await row(id))!.cleanup_next_at).toBe(sweptAt + RECONCILE);
    // The superseded attempt’s R2 request completes only now, after the grace sweep.
    const late = `exports/${id}/${crypto.randomUUID()}/export.zip`;
    await env.ARCHIVE_BUCKET.put(late, 'zip of a lost attempt landing late');
    expect((await expireExports(env, sweptAt + RECONCILE - 1)).removed).toBe(0);
    expect(await keysOf(id)).toEqual([...published, late].sort());
    // Reconciliation removes it and only it; a failed one is retried and the export stays downloadable throughout.
    const failingOnce = bucketWith({ delete: failing });
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: failingOnce }, sweptAt + RECONCILE)).removed).toBe(0);
    expect((await row(id))!.cleanup_next_at).toBe(sweptAt + RECONCILE + RETRY);
    expect(await keysOf(id)).toEqual([...published, late].sort());
    expect((await downloadAt(id, sweptAt + RECONCILE)).status).toBe(200);
    expect((await expireExports(env, sweptAt + RECONCILE + RETRY)).removed).toBe(1);
    expect(await keysOf(id)).toEqual(published);
    expect(Array.from((await downloadAt(id, sweptAt + RECONCILE + RETRY)).body.data as Uint8Array)).toEqual(Array.from(winnerBytes));
    expect(await row(id)).toMatchObject({ state: 'ready', object_key: winner.object_key, manifest_key: winner.manifest_key, attempt: winner.attempt });
    expect((await row(id))!.cleanup_next_at).toBe(sweptAt + RECONCILE + RETRY + RECONCILE);
  });

  it('removes everything at expiry on time even though the next reconciliation was further away, and retries a failed expiry', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    expect(await runPending('export', T0)).toEqual(['complete']);
    const ready = (await row(id))!;
    const published = [ready.object_key, ready.manifest_key].sort();
    // Reconciled shortly before expiry: the next one is due at expiry, not an interval later.
    const lastBefore = ready.expires_at - 60 * MIN;
    expect((await expireExports(env, lastBefore)).removed).toBe(0);
    expect((await row(id))!.cleanup_next_at).toBe(ready.expires_at);
    await env.ARCHIVE_BUCKET.put(`exports/${id}/${crypto.randomUUID()}/export.zip`, 'late');
    const flaky = { CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: bucketWith({ delete: failing }) };
    expect((await expireExports(flaky, ready.expires_at - 1)).removed).toBe(0);
    expect((await expireExports(flaky, ready.expires_at)).removed).toBe(0);
    // The failed expiry changed nothing: still ready on record, objects present, download refused as expired.
    expect(await row(id)).toMatchObject({ state: 'ready', cleanup_next_at: ready.expires_at + RETRY });
    expect(await keysOf(id)).toHaveLength(3);
    expect((await downloadAt(id, ready.expires_at)).status).toBe(410);
    expect((await expireExports(env, ready.expires_at + RETRY)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'expired', object_key: null, manifest_key: null, cleanup_next_at: ready.expires_at + RETRY + RECONCILE });
    expect(published).toHaveLength(2);
    // An expired export is still reconciled.
    await env.ARCHIVE_BUCKET.put(`exports/${id}/after-expiry/export.zip`, 'late');
    expect((await expireExports(env, ready.expires_at + RETRY + RECONCILE)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
  });

  it('removes a published pair at the next pass when a deletion invalidates a ready export that is waiting for its reconciliation', async () => {
    const a = await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    expect(await runPending('export', T0)).toEqual(['complete']);
    const sweptAt = T0 + GRACE;
    expect((await expireExports(env, sweptAt)).removed).toBe(0);
    expect((await row(id))!.cleanup_next_at).toBe(sweptAt + RECONCILE);
    expect(await keysOf(id)).toHaveLength(2);
    await deleteForReal(a, sweptAt + MIN);
    expect(await statusAt(id, sweptAt + MIN)).toMatchObject({ state: 'invalidated', invalidatedReason: 'deleted' });
    expect(await row(id)).toMatchObject({ cleanup_next_at: null });
    // Within the next scheduled pass, not at the reconciliation that was due hours later.
    expect((await expireExports(env, sweptAt + 2 * MIN)).removed).toBe(1);
    expect(await keysOf(id)).toEqual([]);
  });

  it('builds nothing for a pending export that a deletion has already invalidated', async () => {
    const a = await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    await deleteForReal(a);
    // The deletion invalidated the pending export, so a claim that arrives afterwards builds nothing.
    expect(await runPending('export')).toEqual(['complete']);
    expect(await keysOf(id)).toEqual([]);
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM export_sources').first<{ n: number }>())!.n).toBe(0);
  });
});
