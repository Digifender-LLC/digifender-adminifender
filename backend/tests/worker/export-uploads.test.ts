import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { archiveDeletionStatements, planArchiveDeletion } from '../../src/deletion/archive';
import { EXPORT_RECONCILE_MS, STALE_UPLOAD_GRACE_MS, expireExports } from '../../src/exports/job';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { LEASE_EXPIRED, LEASE_MS, MAX_ATTEMPTS, retryDelay, runJob } from '../../src/jobs/runner';
import { T0, api, enrollOwner, expectContract, ingest, quietEnv, runPending } from './support';

const MIN = 60_000;
const GRACE = STALE_UPLOAD_GRACE_MS;
const RETRY = 15 * MIN;
let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-b200-${String((keyCount += 1)).padStart(12, '0')}`;

const mail = (subject: string, body: string) =>
  ['From: ops@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, '', body, ''].join('\r\n');
const create = async () => {
  const res = await api('POST', '/exports', { token, key: key(), body: {}, now: T0 });
  expect(res.status).toBe(200);
  return res.body.data.id as string;
};
const jobOf = async (id: string) => (await env.CONTROL_DB.prepare('SELECT id FROM jobs WHERE dedupe_key = ?').bind(`export:${id}`).first<{ id: string }>())!.id;
const row = (id: string) => env.CONTROL_DB.prepare('SELECT * FROM exports WHERE id = ?').bind(id).first<Record<string, any>>();
const keysOf = async (id: string) => (await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects.map((o) => o.key).sort();
const records = async (id: string) =>
  (await env.CONTROL_DB.prepare('SELECT attempt, upload_id, object_key FROM export_uploads WHERE export_id = ? ORDER BY created_at, attempt').bind(id).all<{ attempt: string; upload_id: string; object_key: string }>()).results;
type Recorded = { attempt: string; upload_id: string; object_key: string };
/** Whether R2 still has this multipart upload: a part sent to an aborted or finished upload is refused with NoSuchUpload (10024). */
async function alive(upload: Recorded): Promise<boolean> {
  try {
    await env.ARCHIVE_BUCKET.resumeMultipartUpload(upload.object_key, upload.upload_id).uploadPart(1, new Uint8Array(8));
    return true;
  } catch (error) {
    expect((error as Error).message).toContain('(10024)');
    return false;
  }
}
async function deleteForReal(sourceId: string, now = T0) {
  const quiet = quietEnv();
  const plan = await planArchiveDeletion(quiet, [sourceId]);
  await env.CONTROL_DB.batch(await archiveDeletionStatements(quiet, plan, crypto.randomUUID(), now));
}

/**
 * The real bucket with hooks on multipart uploads: `afterCreate` runs once the upload exists, `abortFails` makes an
 * attempt's own abort fail, `completeFails` makes completing fail, `resumeFails` makes the sweep's abort fail,
 * `resumed` lists what the sweep tried to abort and `aborted` what an attempt aborted itself.
 */
function uploadBucket(options: {
  afterCreate?: (upload: R2MultipartUpload) => Promise<void>;
  beforeComplete?: () => Promise<void>;
  beforeGet?: () => Promise<void>;
  abortFails?: boolean;
  completeFails?: boolean;
  resumeFails?: () => boolean;
  resumed?: string[];
  aborted?: string[];
}): R2Bucket {
  const injected = () => {
    throw new Error('injected R2 failure');
  };
  return new Proxy(env.ARCHIVE_BUCKET, {
    get(target, prop) {
      const member = Reflect.get(target, prop) as unknown;
      if (prop === 'createMultipartUpload') {
        return async (...args: Parameters<R2Bucket['createMultipartUpload']>) => {
          const upload = await target.createMultipartUpload(...args);
          await options.afterCreate?.(upload);
          return {
            key: upload.key,
            uploadId: upload.uploadId,
            uploadPart: upload.uploadPart.bind(upload),
            abort: async () => {
              options.aborted?.push(upload.uploadId);
              if (options.abortFails) injected();
              return upload.abort();
            },
            complete: async (parts: R2UploadedPart[]) => {
              await options.beforeComplete?.();
              if (options.completeFails) injected();
              return upload.complete(parts);
            },
          } as R2MultipartUpload;
        };
      }
      if (prop === 'get' && options.beforeGet) {
        return async (...args: Parameters<R2Bucket['get']>) => {
          await options.beforeGet?.();
          return target.get(...args);
        };
      }
      if (prop === 'resumeMultipartUpload') {
        return (objectKey: string, uploadId: string) => {
          options.resumed?.push(uploadId);
          const upload = target.resumeMultipartUpload(objectKey, uploadId);
          return options.resumeFails?.() ? ({ key: objectKey, uploadId, abort: async () => injected() } as unknown as R2MultipartUpload) : upload;
        };
      }
      return typeof member === 'function' ? member.bind(target) : member;
    },
  }) as R2Bucket;
}

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('unfinished multipart uploads are tracked, because R2 does not list their parts', () => {
  it('records the upload while it is unfinished and forgets it once it is complete', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    let seen: Recorded[] = [];
    const bucket = uploadBucket({ beforeComplete: async () => { seen = await records(id); } });
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: bucket })).toEqual(['complete']);
    expect(seen).toHaveLength(1);
    expect(seen[0]!.object_key).toBe((await row(id))!.object_key);
    expect(seen[0]!.attempt).toBe((await row(id))!.attempt);
    expect(await records(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'ready' });
  });

  it('shows a tracked upload that R2 does not list, and aborts it from the sweep after the attempt’s own abort failed', async () => {
    const a = await ingest(mail('Invoice', 'deleted-while-building'));
    const id = await create();
    // A deletion closes the export while the ZIP is being built, and this attempt cannot abort its own upload.
    const racing = uploadBucket({ afterCreate: () => deleteForReal(a), abortFails: true });
    expect(await runPending('export', T0, { ARCHIVE_BUCKET: racing })).toEqual(['complete']);
    expect(await row(id)).toMatchObject({ state: 'invalidated' });
    const [upload, ...rest] = await records(id);
    expect(rest).toEqual([]);
    expect(upload!.object_key).toMatch(new RegExp(`^exports/${id}/[0-9a-f-]+/export\\.zip$`));
    // R2 lists no object for it, so a sweep by prefix alone would never have found it; the upload itself is still open.
    expect(await keysOf(id)).toEqual([]);
    expect(await alive(upload!)).toBe(true);

    // The abort fails again in the sweep: the export is retried in 15 minutes, nothing is forgotten, and the failure is not hidden.
    const resumed: string[] = [];
    const broken = uploadBucket({ resumeFails: () => true, resumed });
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: broken }, T0 + MIN)).removed).toBe(0);
    expect(resumed).toEqual([upload!.upload_id]);
    expect(await records(id)).toHaveLength(1);
    expect(await alive(upload!)).toBe(true);
    expect((await row(id))!.cleanup_next_at).toBe(T0 + MIN + RETRY);
    // Not before the retry time...
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: uploadBucket({ resumed }) }, T0 + MIN + RETRY - 1)).removed).toBe(0);
    expect(await alive(upload!)).toBe(true);
    // ...then it is aborted, forgotten, and the export is back on the ordinary schedule.
    expect((await expireExports(env, T0 + MIN + RETRY)).removed).toBe(1);
    expect(await alive(upload!)).toBe(false);
    expect(await records(id)).toEqual([]);
    // (Due again once the quiet period after settling has passed, then every reconciliation interval.)
    expect((await row(id))!.cleanup_next_at).toBe(T0 + GRACE);
    // A repeat visit finds nothing and does not fail on the vanished upload.
    expect((await expireExports(env, T0 + GRACE)).removed).toBe(0);
    expect((await row(id))!.cleanup_next_at).toBe(T0 + GRACE + EXPORT_RECONCILE_MS);
  });

  it('aborts the uploads of every attempt of an export whose job died, however each attempt ended', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    const jobId = await jobOf(id);
    // Every attempt creates an upload, fails to complete it and fails to abort it: the state a hard-killed invocation leaves.
    const dying = { ...quietEnv(), ARCHIVE_BUCKET: uploadBucket({ completeFails: true, abortFails: true }) };
    let at = T0;
    const statuses: string[] = [];
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      statuses.push((await runJob(dying, JOB_HANDLERS, jobId, at)).status);
      at += retryDelay(attempt) + 1;
    }
    expect(statuses).toEqual(['retry', 'retry', 'retry', 'retry', 'dead']);
    const open = await records(id);
    expect(open).toHaveLength(5);
    expect(new Set(open.map((u) => u.attempt)).size).toBe(5);
    for (const upload of open) expect(await alive(upload)).toBe(true);
    expect(await keysOf(id)).toEqual([]);
    // The export reads as failed (never as ready or empty) and the sweep reaches every upload.
    const status = await api('GET', `/exports/${id}`, { token, now: T0 });
    expectContract('export-status', status.body.data);
    expect(status.body.data).toMatchObject({ state: 'failed', failureCode: 'handler_exception' });
    expect((await expireExports(env, at)).removed).toBe(1);
    for (const upload of open) expect(await alive(upload)).toBe(false);
    expect(await records(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'failed' });
  });

  it('ends an export whose every invocation was killed before it could settle, reports it failed and aborts every recorded upload', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    const jobId = await jobOf(id);
    // Each attempt creates and records its multipart upload and then never returns, as when the runtime ends the invocation
    // before the result reaches D1: the job stays `running` on a lease that simply runs out. The gates stand in for the
    // killed invocations and are only opened at the end, so no test promise is left dangling.
    const runs: ReturnType<typeof runJob>[] = [];
    const gates: (() => void)[] = [];
    for (let n = 0; n < MAX_ATTEMPTS; n += 1) {
      let reached!: () => void;
      const hung = new Promise<void>((resolve) => (reached = resolve));
      let release!: () => void;
      const gate = new Promise<void>((resolve) => (release = resolve));
      gates.push(release);
      runs.push(runJob({ ...quietEnv(), ARCHIVE_BUCKET: uploadBucket({ beforeGet: async () => { reached(); await gate; } }) }, JOB_HANDLERS, jobId, T0 + n * (LEASE_MS + 1)));
      await hung;
    }
    const open = await records(id);
    expect(open).toHaveLength(MAX_ATTEMPTS);
    expect(new Set(open.map((u) => u.attempt)).size).toBe(MAX_ATTEMPTS);
    const lastLeaseEnds = T0 + (MAX_ATTEMPTS - 1) * (LEASE_MS + 1) + LEASE_MS;
    // While the last lease is live the export is honestly still being built, and nothing is swept (the API clock stays at T0 for the session).
    expect((await api('GET', `/exports/${id}`, { token, now: T0 })).body.data).toMatchObject({ state: 'running', failureCode: null });
    expect((await expireExports(env, lastLeaseEnds)).removed).toBe(0);
    for (const upload of open) expect(await alive(upload)).toBe(true);

    // The lease runs out with the cap spent: the next delivery ends the job instead of starting a sixth attempt.
    const after = lastLeaseEnds + 1;
    expect(await runJob(quietEnv(), JOB_HANDLERS, jobId, after)).toEqual({ jobId, status: 'dead' });
    expect(await env.CONTROL_DB.prepare('SELECT state, attempt, error_code, lease_token FROM jobs WHERE id = ?').bind(jobId).first()).toEqual({ state: 'dead', attempt: MAX_ATTEMPTS, error_code: LEASE_EXPIRED, lease_token: null });
    expect(await records(id)).toHaveLength(MAX_ATTEMPTS);
    const status = await api('GET', `/exports/${id}`, { token, now: T0 });
    expectContract('export-status', status.body.data);
    expect(status.body.data).toMatchObject({ state: 'failed', failureCode: LEASE_EXPIRED, counts: null, omissions: null });
    const download = await api('GET', `/exports/${id}/content`, { token, now: T0 });
    expect(download.status).toBe(409);
    expect(download.body.error?.code).toBe('export_failed');

    // The sweep aborts all recorded uploads, though R2 lists no object for any of them.
    expect(await keysOf(id)).toEqual([]);
    expect((await expireExports(env, after)).removed).toBe(1);
    for (const upload of open) expect(await alive(upload)).toBe(false);
    expect(await records(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'failed', object_key: null, manifest_key: null });

    // The invocations that were thought killed wake up late: none can change the dead job or leave an object behind.
    for (const release of gates) release();
    expect((await Promise.all(runs)).map((r) => r.status)).toEqual(Array.from({ length: MAX_ATTEMPTS }, () => 'stale'));
    expect(await env.CONTROL_DB.prepare('SELECT state, attempt, error_code FROM jobs WHERE id = ?').bind(jobId).first()).toEqual({ state: 'dead', attempt: MAX_ATTEMPTS, error_code: LEASE_EXPIRED });
    expect(await keysOf(id)).toEqual([]);
    expect(await records(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'failed' });
  });

  it('aborts a superseded attempt’s upload after the grace and leaves the winner’s ZIP, manifest and download alone', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    const jobId = await jobOf(id);
    // Attempt A stops once its upload is created and recorded (its lease is about to be lost) and then cannot abort it.
    let reached!: () => void;
    const created = new Promise<void>((resolve) => (reached = resolve));
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const older = runJob(
      { ...quietEnv(), ARCHIVE_BUCKET: uploadBucket({ beforeGet: async () => { reached(); await gate; }, abortFails: true }) },
      JOB_HANDLERS,
      jobId,
      T0,
    );
    await created;
    const staleUpload = (await records(id))[0]!;
    // B claims after A's lease ran out and publishes.
    const newerAt = T0 + LEASE_MS + 1;
    expect(await runJob(quietEnv(), JOB_HANDLERS, jobId, newerAt)).toEqual({ jobId, status: 'complete' });
    const winner = (await row(id))!;
    expect(winner).toMatchObject({ state: 'ready' });
    release();
    expect(await older).toEqual({ jobId, status: 'stale' });
    // A found it had lost the lease and tried to abort; that failed, so its record is the only trace.
    expect(await records(id)).toEqual([staleUpload]);
    expect(staleUpload.attempt).not.toBe(winner.attempt);
    expect(await alive(staleUpload)).toBe(true);
    // A record left for the winner too (the process died between completing and forgetting) must never be aborted.
    const winnerRecord = { attempt: winner.attempt as string, upload_id: 'winner-upload-id', object_key: winner.object_key as string };
    await env.CONTROL_DB.prepare('INSERT INTO export_uploads(export_id, attempt, upload_id, object_key, created_at) VALUES (?, ?, ?, ?, ?)').bind(id, winnerRecord.attempt, winnerRecord.upload_id, winnerRecord.object_key, newerAt).run();

    // Inside the grace a ready export is not visited at all.
    expect((await expireExports(env, newerAt + 10 * MIN)).removed).toBe(0);
    expect(await alive(staleUpload)).toBe(true);
    const resumed: string[] = [];
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: uploadBucket({ resumed }) }, newerAt + GRACE)).removed).toBe(1);
    expect(resumed).toEqual([staleUpload.upload_id]);
    expect(await alive(staleUpload)).toBe(false);
    expect(await records(id)).toEqual([]);
    expect(await row(id)).toMatchObject({ state: 'ready', object_key: winner.object_key, manifest_key: winner.manifest_key, attempt: winner.attempt });
    expect(await keysOf(id)).toEqual([winner.object_key, winner.manifest_key].sort());
    const download = await api('GET', `/exports/${id}/content`, { token, now: T0 });
    expect(download.status).toBe(200);
  });

  it('does not abort the upload of an export that is still being built', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    const jobId = await jobOf(id);
    let reached!: () => void;
    const created = new Promise<void>((resolve) => (reached = resolve));
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    const building = runJob({ ...quietEnv(), ARCHIVE_BUCKET: uploadBucket({ beforeGet: async () => { reached(); await gate; } }) }, JOB_HANDLERS, jobId, T0);
    await created;
    const [live] = await records(id);
    // However late the pass runs, a running export with a live job is not swept.
    const resumed: string[] = [];
    expect((await expireExports({ CONTROL_DB: env.CONTROL_DB, ARCHIVE_BUCKET: uploadBucket({ resumed }) }, T0 + 3 * EXPORT_RECONCILE_MS)).removed).toBe(0);
    expect(resumed).toEqual([]);
    expect(await alive(live!)).toBe(true);
    release();
    expect(await building).toEqual({ jobId, status: 'complete' });
    expect(await row(id)).toMatchObject({ state: 'ready' });
    expect(await records(id)).toEqual([]);
  });

  it('aborts at once when the upload cannot be recorded, and raises the failure', async () => {
    await ingest(mail('Invoice', 'ordinary words'));
    const id = await create();
    const jobId = await jobOf(id);
    const created: R2MultipartUpload[] = [];
    const aborts: string[] = [];
    const bucket = uploadBucket({ afterCreate: async (upload) => void created.push(upload), aborted: aborts });
    const db = new Proxy(env.CONTROL_DB, {
      get(target, prop) {
        const member = Reflect.get(target, prop) as unknown;
        if (prop === 'prepare') {
          return (sql: string) => {
            if (sql.includes('INSERT INTO export_uploads')) throw new Error('injected D1 failure');
            return target.prepare(sql);
          };
        }
        return typeof member === 'function' ? member.bind(target) : member;
      },
    }) as D1Database;
    // The handler's own D1 handle is the failing one; the runner's claim and settle use the real one.
    const claimEnv = { ...quietEnv(), ARCHIVE_BUCKET: bucket };
    const result = await runJob({ ...claimEnv, CONTROL_DB: db } as typeof claimEnv, JOB_HANDLERS, jobId, T0);
    expect(result.status).toBe('retry');
    expect(created).toHaveLength(1);
    expect(aborts).toEqual([created[0]!.uploadId]);
    expect(await alive({ attempt: 'x', upload_id: created[0]!.uploadId, object_key: created[0]!.key })).toBe(false);
    expect(await records(id)).toEqual([]);
    // Nothing was published and the export is not reported as ready.
    expect(await row(id)).toMatchObject({ state: 'running', object_key: null });
  });
});
