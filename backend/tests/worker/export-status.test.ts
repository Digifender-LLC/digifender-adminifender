import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { retryDelay, runJob } from '../../src/jobs/runner';
import { T0, api, enrollOwner, expectContract, ingest, quietEnv, runPending } from './support';

let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-b300-${String((keyCount += 1)).padStart(12, '0')}`;
const mail = (subject: string, body: string) =>
  ['From: ops@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, '', body, ''].join('\r\n');
const row = (id: string) => env.CONTROL_DB.prepare('SELECT * FROM exports WHERE id = ?').bind(id).first<Record<string, any>>();
const status = (id: string) => api('GET', `/exports/${id}`, { token, now: T0 });

async function readyExport(): Promise<string> {
  await ingest(mail('Invoice', 'ordinary words'));
  const created = await api('POST', '/exports', { token, key: key(), body: {}, now: T0 });
  expect(created.status).toBe(200);
  expect(await runPending('export')).toEqual(['complete']);
  return created.body.data.id as string;
}

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('GET /exports/{id} for a ready export fails closed when the manifest cannot be trusted', () => {
  it('reports counts and omissions from a readable manifest, matching the shared contract', async () => {
    const id = await readyExport();
    const res = await status(id);
    expect(res.status).toBe(200);
    expectContract('export-status', res.body.data);
    expect(res.body.data).toMatchObject({ state: 'ready', counts: { sources: 1, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [] });
  });

  it('refuses a ready export whose manifest object is gone, with an explicit error and no null or empty counts', async () => {
    const id = await readyExport();
    await env.ARCHIVE_BUCKET.delete((await row(id))!.manifest_key);
    const res = await status(id);
    expect(res.status).toBe(500);
    expect(res.body.error?.code).toBe('export_manifest_unavailable');
    expect(res.body.data).toBeUndefined();
    // Nothing of the export's content is in the error.
    expect(JSON.stringify(res.body)).not.toMatch(/Invoice|ordinary words|vendor\.example/);
  });

  it('refuses a ready export that has no manifest key recorded', async () => {
    const id = await readyExport();
    await env.CONTROL_DB.prepare('UPDATE exports SET manifest_key = NULL WHERE id = ?').bind(id).run();
    const res = await status(id);
    expect(res.status).toBe(500);
    expect(res.body.error?.code).toBe('export_manifest_unavailable');
  });

  it.each([
    ['not JSON', 'this is not json'],
    ['JSON null', 'null'],
    ['no counts', JSON.stringify({ omissions: [] })],
    ['null counts', JSON.stringify({ counts: null, omissions: [] })],
    ['a count missing', JSON.stringify({ counts: { sources: 1, attachments: 0, concerns: 0, rules: 0 }, omissions: [] })],
    ['a negative count', JSON.stringify({ counts: { sources: -1, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [] })],
    ['a fractional count', JSON.stringify({ counts: { sources: 1.5, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [] })],
    ['a string count', JSON.stringify({ counts: { sources: '1', attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [] })],
    ['no omissions', JSON.stringify({ counts: { sources: 1, attachments: 0, concerns: 0, rules: 0, services: 0 } })],
    ['omissions that are not a list', JSON.stringify({ counts: { sources: 1, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: {} })],
    ['an unknown omission reason', JSON.stringify({ counts: { sources: 1, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [{ sourceId: 's', reason: 'because' }] })],
    ['an omission without a source', JSON.stringify({ counts: { sources: 1, attachments: 0, concerns: 0, rules: 0, services: 0 }, omissions: [{ reason: 'deleted' }] })],
  ])('refuses a ready export whose manifest has %s', async (_label, manifest) => {
    const id = await readyExport();
    await env.ARCHIVE_BUCKET.put((await row(id))!.manifest_key, manifest);
    const res = await status(id);
    expect(res.status).toBe(500);
    expect(res.body.error?.code).toBe('export_manifest_invalid');
    expect(res.body.data).toBeUndefined();
  });

  it('only reads the manifest of a ready export: other states answer without it', async () => {
    const id = await readyExport();
    await env.ARCHIVE_BUCKET.delete((await row(id))!.manifest_key);
    await env.CONTROL_DB.prepare("UPDATE exports SET state = 'expired' WHERE id = ?").bind(id).run();
    const res = await status(id);
    expect(res.status).toBe(200);
    expectContract('export-status', res.body.data);
    expect(res.body.data).toMatchObject({ state: 'expired', counts: null, omissions: null });
  });
});

describe('a build that runs into a platform limit fails visibly', () => {
  it('ends failed, with nothing published and nothing truncated, when R2 starts refusing calls partway through', async () => {
    // What a Worker invocation does when it passes its subrequest limit: the next binding call throws. Here R2 reads
    // work twice and then throw, standing in for any export too large for one invocation.
    await ingest(mail('First', 'one'));
    await ingest(mail('Second', 'two'), { now: T0 + 1 });
    await ingest(mail('Third', 'three'), { now: T0 + 2 });
    const created = await api('POST', '/exports', { token, key: key(), body: {}, now: T0 });
    const id = created.body.data.id as string;
    const jobId = (await env.CONTROL_DB.prepare('SELECT id FROM jobs WHERE dedupe_key = ?').bind(`export:${id}`).first<{ id: string }>())!.id;
    let reads = 0;
    const limited = new Proxy(env.ARCHIVE_BUCKET, {
      get(target, prop) {
        const member = Reflect.get(target, prop) as unknown;
        if (prop === 'get') {
          return async (...args: Parameters<R2Bucket['get']>) => {
            reads += 1;
            if (reads > 2) throw new Error('Too many subrequests by single Worker invocation.');
            return target.get(...args);
          };
        }
        return typeof member === 'function' ? member.bind(target) : member;
      },
    }) as R2Bucket;
    let at = T0;
    const outcomes: string[] = [];
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      reads = 0;
      outcomes.push((await runJob({ ...quietEnv(), ARCHIVE_BUCKET: limited }, JOB_HANDLERS, jobId, at)).status);
      at += retryDelay(attempt) + 1;
    }
    expect(outcomes).toEqual(['retry', 'retry', 'retry', 'retry', 'dead']);
    const res = await status(id);
    expect(res.status).toBe(200);
    expectContract('export-status', res.body.data);
    expect(res.body.data).toMatchObject({ state: 'failed', failureCode: 'handler_exception', counts: null, omissions: null });
    const download = await api('GET', `/exports/${id}/content`, { token, now: T0 });
    expect(download.status).toBe(409);
    expect(download.body.error?.code).toBe('export_failed');
    expect(await row(id)).toMatchObject({ object_key: null, manifest_key: null });
    // No ZIP completed and no upload is left open.
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects).toEqual([]);
    expect((await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM export_uploads WHERE export_id = ?').bind(id).first<{ n: number }>())!.n).toBe(0);
  });
});
