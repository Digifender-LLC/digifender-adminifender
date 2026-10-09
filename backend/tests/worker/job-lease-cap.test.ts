import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { reconcile } from '../../src/jobs/reconcile';
import { CLAIM_GUARD, type ClaimedJob, type JobHandler, LEASE_EXPIRED, LEASE_MS, MAX_ATTEMPTS, claimJob, guardParams, retryDelay, runJob, settleExpiredAtCap } from '../../src/jobs/runner';
import { T0, quietEnv } from './support';

const db = env.CONTROL_DB;
const jobRow = (id: string) => db.prepare('SELECT state, attempt, error_code, lease_token, lease_until, output_json FROM jobs WHERE id = ?').bind(id).first<Record<string, any>>();

/** A synthetic pending job of a kind only the test's own handler knows. */
async function newJob(state = 'pending'): Promise<string> {
  const id = crypto.randomUUID();
  await db.prepare("INSERT INTO jobs(id, dedupe_key, kind, state, next_at, input_json, created_at) VALUES (?, ?, 'cap_probe', ?, ?, '{}', ?)").bind(id, `cap:${id}`, state, T0, T0).run();
  return id;
}
/** Claims `count` times, each after the previous lease ran out, and never settles: invocations that were killed. Returns the last claim and its time. */
async function abandon(jobId: string, count: number): Promise<{ claim: ClaimedJob; at: number }> {
  let claim: ClaimedJob | null = null;
  let at = T0;
  for (let i = 0; i < count; i += 1) {
    at = T0 + i * (LEASE_MS + 1);
    claim = await claimJob(db, jobId, at);
    expect(claim?.attempt).toBe(i + 1);
  }
  return { claim: claim!, at };
}

describe('an expired lease cannot reclaim a job past the attempt cap', () => {
  it('restarts an abandoned attempt while attempts remain, numbering them up to the cap', async () => {
    const jobId = await newJob();
    const { claim, at } = await abandon(jobId, MAX_ATTEMPTS);
    expect(claim.attempt).toBe(MAX_ATTEMPTS);
    expect(await jobRow(jobId)).toMatchObject({ state: 'running', attempt: MAX_ATTEMPTS, lease_token: claim.leaseToken, lease_until: at + LEASE_MS });
  });

  it('never numbers a claim past the cap, even for a caller that has not ended the job first', async () => {
    const jobId = await newJob();
    const { claim, at } = await abandon(jobId, MAX_ATTEMPTS);
    expect(await claimJob(db, jobId, at + LEASE_MS + 1)).toBeNull();
    expect(await jobRow(jobId)).toMatchObject({ state: 'running', attempt: MAX_ATTEMPTS, lease_token: claim.leaseToken });
  });

  it.each(['running', 'leased'])('ends a %s job whose last permitted attempt expired, content-free, with its lease cleared', async (state) => {
    const jobId = await newJob();
    const { at } = await abandon(jobId, MAX_ATTEMPTS);
    if (state === 'leased') await db.prepare("UPDATE jobs SET state = 'leased' WHERE id = ?").bind(jobId).run();
    let ran = 0;
    const handlers: Record<string, JobHandler> = { cap_probe: async () => (ran += 1, { kind: 'complete', output: null }) };
    const later = at + LEASE_MS + 1;
    expect(await runJob(quietEnv(), handlers, jobId, later)).toEqual({ jobId, status: 'dead' });
    expect(ran).toBe(0);
    expect(await jobRow(jobId)).toEqual({ state: 'dead', attempt: MAX_ATTEMPTS, error_code: LEASE_EXPIRED, lease_token: null, lease_until: null, output_json: null });
    // Nothing claims or republishes it again, however long it waits.
    expect(await claimJob(db, jobId, later + 365 * 24 * 60 * 60_000)).toBeNull();
    expect(await runJob(quietEnv(), handlers, jobId, later + 1)).toEqual({ jobId, status: 'skipped' });
    const quiet = quietEnv();
    await reconcile(quiet, later + LEASE_MS);
    expect(quiet.sent.filter((m) => m.jobId === jobId)).toEqual([]);
    expect((await jobRow(jobId))!.attempt).toBe(MAX_ATTEMPTS);
  });

  it('leaves a live lease alone, at the cap too, including at the instant it ends', async () => {
    const jobId = await newJob();
    const { claim, at } = await abandon(jobId, MAX_ATTEMPTS);
    const live = { state: 'running', attempt: MAX_ATTEMPTS, error_code: null, lease_token: claim.leaseToken, lease_until: at + LEASE_MS };
    for (const now of [at + 1, at + LEASE_MS - 1, at + LEASE_MS]) {
      expect(await settleExpiredAtCap(db, now, jobId)).toBe(0);
      expect(await settleExpiredAtCap(db, now)).toBe(0);
      expect(await claimJob(db, jobId, now)).toBeNull();
      expect(await runJob(quietEnv(), {}, jobId, now)).toEqual({ jobId, status: 'skipped' });
      expect(await jobRow(jobId)).toMatchObject(live);
    }
    expect(await settleExpiredAtCap(db, at + LEASE_MS + 1, jobId)).toBe(1);
  });

  it('does not end a job that is still below the cap, or one that is waiting to retry', async () => {
    const below = await newJob();
    const { at } = await abandon(below, MAX_ATTEMPTS - 1);
    expect(await settleExpiredAtCap(db, at + LEASE_MS + 1, below)).toBe(0);
    expect((await claimJob(db, below, at + LEASE_MS + 1))?.attempt).toBe(MAX_ATTEMPTS);
    // A job whose last attempt settled as a retry has no lease to expire; it stays pending and is never claimed past the cap.
    const waiting = await newJob();
    await db.prepare("UPDATE jobs SET attempt = ?, next_at = ? WHERE id = ?").bind(MAX_ATTEMPTS - 1, T0 + retryDelay(MAX_ATTEMPTS - 1), waiting).run();
    expect(await settleExpiredAtCap(db, T0 + 365 * 24 * 60 * 60_000, waiting)).toBe(0);
    expect((await claimJob(db, waiting, T0 + retryDelay(MAX_ATTEMPTS - 1)))?.attempt).toBe(MAX_ATTEMPTS);
  });

  it('ends overrun jobs from the scheduled pass without waiting for a queue message, a bounded batch at a time', async () => {
    const ids: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const id = await newJob();
      await db.prepare("UPDATE jobs SET state = 'running', attempt = ?, lease_token = 'gone', lease_until = ? WHERE id = ?").bind(MAX_ATTEMPTS, T0 + i, id).run();
      ids.push(id);
    }
    const quiet = quietEnv();
    await reconcile(quiet, T0 + 10);
    for (const id of ids) expect(await jobRow(id)).toMatchObject({ state: 'dead', error_code: LEASE_EXPIRED, lease_token: null });
    expect(quiet.sent.filter((m) => ids.includes(m.jobId))).toEqual([]);
    expect(await settleExpiredAtCap(db, T0 + 10)).toBe(0);
    // The limit bounds one pass.
    const more = [await newJob(), await newJob()];
    for (const id of more) await db.prepare("UPDATE jobs SET state = 'running', attempt = ?, lease_token = 'gone', lease_until = ? WHERE id = ?").bind(MAX_ATTEMPTS, T0, id).run();
    expect(await settleExpiredAtCap(db, T0 + 10, undefined, 1)).toBe(1);
    expect(await settleExpiredAtCap(db, T0 + 10, undefined, 1)).toBe(1);
    expect(await settleExpiredAtCap(db, T0 + 10, undefined, 1)).toBe(0);
  });

  describe('a stale attempt cannot bring a dead job back', () => {
    /** Attempt MAX_ATTEMPTS runs a handler that waits on `release`; while it waits its lease expires and the job is ended. */
    async function overrun(result: JobHandler) {
      const jobId = await newJob();
      const { at } = await abandon(jobId, MAX_ATTEMPTS - 1);
      const startAt = at + LEASE_MS + 1;
      let reached!: () => void;
      const started = new Promise<void>((resolve) => (reached = resolve));
      let release!: () => void;
      const gate = new Promise<void>((resolve) => (release = resolve));
      const stale = runJob(quietEnv(), { cap_probe: async (job, e, now) => (reached(), await gate, result(job, e, now)) }, jobId, startAt);
      await started;
      expect(await jobRow(jobId)).toMatchObject({ state: 'running', attempt: MAX_ATTEMPTS });
      expect(await runJob(quietEnv(), {}, jobId, startAt + LEASE_MS + 1)).toEqual({ jobId, status: 'dead' });
      const dead = await jobRow(jobId);
      release();
      return { jobId, stale, dead };
    }

    it.each([
      ['a completion', async (): ReturnType<JobHandler> => Promise.resolve({ kind: 'complete', output: { late: true } })],
      ['a retry', async (): ReturnType<JobHandler> => Promise.resolve({ kind: 'retry', errorCode: 'late' })],
      ['a failure', async (): ReturnType<JobHandler> => Promise.resolve({ kind: 'fail', errorCode: 'late' })],
      ['a deferral', async (): ReturnType<JobHandler> => Promise.resolve({ kind: 'defer', until: T0 + 60_000 })],
      ['an exception', async (): ReturnType<JobHandler> => Promise.reject(new Error('late'))],
    ])('is not revived by %s settled after the job was ended', async (_name, result) => {
      const { jobId, stale, dead } = await overrun(result);
      expect(dead).toMatchObject({ state: 'dead', error_code: LEASE_EXPIRED, lease_token: null });
      expect(await stale).toEqual({ jobId, status: 'stale' });
      expect(await jobRow(jobId)).toEqual(dead);
    });

    it('applies none of the dependent statements of a late completion or failure', async () => {
      const marker = crypto.randomUUID();
      const guarded = (job: ClaimedJob) =>
        db
          .prepare(`INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, ?, 'job', ?, 'pending', ? WHERE ${CLAIM_GUARD}`)
          .bind(marker, `late:${marker}`, job.id, T0, ...guardParams(job));
      for (const kind of ['complete', 'fail'] as const) {
        const { jobId, stale } = await overrun(async (job) =>
          kind === 'complete' ? { kind, output: null, statements: [guarded(job)] } : { kind, errorCode: 'late', statements: [guarded(job)] },
        );
        expect(await stale).toEqual({ jobId, status: 'stale' });
        expect(await db.prepare('SELECT COUNT(*) AS n FROM outbox WHERE id = ?').bind(marker).first()).toEqual({ n: 0 });
      }
    });

    it('does not end a job whose last attempt settled before its lease expired', async () => {
      const jobId = await newJob();
      const { at } = await abandon(jobId, MAX_ATTEMPTS - 1);
      const startAt = at + LEASE_MS + 1;
      expect(await runJob(quietEnv(), { cap_probe: async () => ({ kind: 'complete', output: { ok: true } }) }, jobId, startAt)).toEqual({ jobId, status: 'complete' });
      expect(await settleExpiredAtCap(db, startAt + 10 * LEASE_MS, jobId)).toBe(0);
      expect(await jobRow(jobId)).toMatchObject({ state: 'complete', attempt: MAX_ATTEMPTS, error_code: null });
    });
  });

  it('still allows exactly MAX_ATTEMPTS normal attempts and a dead job after the last retry', async () => {
    const jobId = await newJob();
    const handlers: Record<string, JobHandler> = { cap_probe: async () => ({ kind: 'retry', errorCode: 'again' }) };
    const statuses: string[] = [];
    let at = T0;
    for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt += 1) {
      statuses.push((await runJob(quietEnv(), handlers, jobId, at)).status);
      at += retryDelay(attempt) + 1;
    }
    expect(statuses).toEqual(['retry', 'retry', 'retry', 'retry', 'dead']);
    expect(await jobRow(jobId)).toMatchObject({ state: 'dead', attempt: MAX_ATTEMPTS, error_code: 'again' });
  });
});
