import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { EXPORT_TTL_MS, expireExports } from '../../src/exports/job';
import { inflateEntry, readCentralDirectory } from '../../src/extraction/zip';
import { sha256Hex } from '../../src/ids';
import { T0, api, enrollOwner, expectContract, ingest, runPending } from './support';

let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-b000-${String((keyCount += 1)).padStart(12, '0')}`;
const decoder = new TextDecoder();

const mail = (subject: string, body: string, attachment?: string, filename = 'note.txt') =>
  [
    'From: ops@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${subject}`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain',
    '',
    body,
    ...(attachment ? ['--b1', `Content-Type: text/plain; name="${filename}"`, `Content-Disposition: attachment; filename="${filename}"`, '', attachment] : []),
    '--b1--',
    '',
  ].join('\r\n');

async function unzip(bytes: Uint8Array): Promise<Map<string, Uint8Array>> {
  const out = new Map<string, Uint8Array>();
  for (const entry of readCentralDirectory(bytes)) out.set(entry.name, await inflateEntry(bytes, entry, 20 * 1024 * 1024));
  return out;
}
const lines = (data: Uint8Array | undefined) => decoder.decode(data).trim().split('\n').filter(Boolean).map((l) => JSON.parse(l));

async function createExport(now = T0): Promise<string> {
  const created = await api('POST', '/exports', { token, key: key(), body: {}, now });
  expect(created.status).toBe(200);
  return created.body.data.id;
}

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('exports', () => {
  it('writes records, original files and a checksum manifest at a snapshot boundary', async () => {
    const a = await ingest(mail('Invoice', 'Amount due: 42', 'attached note'));
    await runPending('triage');
    await runPending('extract');
    await api('POST', '/rules', { token, key: key(), body: { predicate: { domain_equals: 'vendor.example' }, effects: [{ surface: true }] } });
    const id = await createExport();
    // Arrives after the snapshot, so it is not part of this export.
    const later = await ingest(mail('Later', 'After the snapshot'), { now: T0 + 1000 });
    expect((await api('GET', `/exports/${id}/content`, { token })).status).toBe(409);
    expect(await runPending('export', T0 + 2000)).toEqual(['complete']);

    const status = (await api('GET', `/exports/${id}`, { token })).body.data;
    expect(status).toMatchObject({ state: 'ready', counts: { sources: 1, attachments: 1, concerns: 1, rules: 1, services: 0 }, omissions: [], expiresAt: new Date(T0 + EXPORT_TTL_MS).toISOString() });
    const download = await api('GET', `/exports/${id}/content`, { token });
    expect(download.headers.get('content-type')).toBe('application/zip');
    expect(download.headers.get('content-disposition')).toBe('attachment; filename="adminifender-export-2026-10-03.zip"');
    const files = await unzip(download.body.data as Uint8Array);

    const manifest = JSON.parse(decoder.decode(files.get('manifest.json')));
    expect(manifest).toMatchObject({ schemaVersion: 2, exportId: id, scope: { kind: 'archive', admission: 'normal' }, omissions: [], sections: { rules: 'included', services: 'included', partialConcerns: 0 } });
    for (const file of manifest.files as { path: string; sha256: string; bytes: number }[]) {
      expect(await sha256Hex(files.get(file.path)!)).toBe(file.sha256);
      expect(files.get(file.path)!.length).toBe(file.bytes);
    }
    const paths = [...files.keys()].sort();
    expect(paths.filter((p) => !p.startsWith('files/'))).toEqual(['attachments.jsonl', 'concerns.jsonl', 'manifest.json', 'rules.jsonl', 'services.jsonl', 'sources.jsonl']);

    const [source] = lines(files.get('sources.jsonl'));
    expect(source).toMatchObject({ id: a, subject: 'Invoice', text: 'Amount due: 42', originalPath: `files/${a}/message.eml`, originalStatus: 'included' });
    expect(decoder.decode(files.get(`files/${a}/message.eml`))).toContain('Subject: Invoice');
    const [attachment] = lines(files.get('attachments.jsonl'));
    expect(attachment).toMatchObject({ sourceId: a, name: 'note.txt', extraction: 'ready' });
    expect(decoder.decode(files.get(attachment.path))).toBe('attached note');
    expect(lines(files.get('concerns.jsonl'))[0]).toMatchObject({ title: 'Invoice', sourceIds: [a] });
    expect(paths.some((p) => p.includes(later))).toBe(false);
    // No secrets or session material.
    const all = [...files.values()].map((f) => decoder.decode(f)).join('\n');
    for (const secret of ['afa_', 'afr_', 'refresh_digest', 'test-only-']) expect(all).not.toContain(secret);
  });

  it.each(['..', '.', '...'])('never uses the dot name %s as a path segment for an attachment', async (filename) => {
    const a = await ingest(mail('Dots', 'Body', 'attached note', filename));
    await runPending('triage');
    await runPending('extract');
    const id = await createExport();
    expect(await runPending('export', T0 + 2000)).toEqual(['complete']);
    const files = await unzip((await api('GET', `/exports/${id}/content`, { token })).body.data as Uint8Array);
    const attachmentPaths = [...files.keys()].filter((p) => p.startsWith(`files/${a}/`) && !p.endsWith('/message.eml'));
    expect(attachmentPaths).toHaveLength(1);
    // The phone refuses to vouch for an export with an empty, `.` or `..` segment, so the Worker must never write one.
    for (const path of files.keys()) expect(path.split('/').every((segment) => segment !== '' && segment !== '.' && segment !== '..')).toBe(true);
  });

  it('lists sources deleted after the snapshot as omitted and never resurrects them', async () => {
    const victim = await ingest(mail('Private', 'secret body'));
    const keep = await ingest(mail('Keep', 'kept body'), { now: T0 + 1 });
    const id = await createExport();
    const review = (await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [victim] } } })).body.data;
    await api('POST', `/reviews/${review.reviewId}/confirm`, { token, key: key(), body: { paramsDigest: review.paramsDigest } });
    // The deletion invalidated the pending export; a new one is needed and excludes the source.
    expect(await runPending('export')).toEqual(['complete']);
    expect((await api('GET', `/exports/${id}/content`, { token })).status).toBe(410);
    await runPending('cleanup');
    const fresh = await createExport();
    await runPending('export');
    const files = await unzip((await api('GET', `/exports/${fresh}/content`, { token })).body.data as Uint8Array);
    expect(lines(files.get('sources.jsonl')).map((s: { id: string }) => s.id)).toEqual([keep]);
    expect(decoder.decode(files.get('sources.jsonl'))).not.toContain('secret body');
  });

  it('omits a source deleted between the snapshot and the build when the export is not yet invalidated', async () => {
    const victim = await ingest(mail('Private', 'secret body'));
    const id = await createExport();
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleting' WHERE id = ?").bind(victim).run();
    await runPending('export');
    const status = (await api('GET', `/exports/${id}`, { token })).body.data;
    expect(status.omissions).toEqual([{ sourceId: victim, reason: 'deleted' }]);
    expect(status.counts.sources).toBe(0);
  });

  it('invalidates ready exports on deletion and removes expired or invalidated objects', async () => {
    const victim = await ingest(mail('Private', 'secret body'));
    const id = await createExport();
    await runPending('export');
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects).toHaveLength(2);
    const review = (await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [victim] } } })).body.data;
    await api('POST', `/reviews/${review.reviewId}/confirm`, { token, key: key(), body: { paramsDigest: review.paramsDigest } });
    expect((await api('GET', `/exports/${id}/content`, { token })).body.error?.code).toBe('deleted');
    expect(await expireExports(env, T0)).toEqual({ removed: 1 });
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects).toEqual([]);

    const other = await createExport(T0 + 1);
    await runPending('export', T0 + 1);
    expect((await api('GET', `/exports/${other}/content`, { token, now: T0 + 2 })).status).toBe(200);
    expect(await expireExports(env, T0 + 1 + EXPORT_TTL_MS)).toEqual({ removed: 1 });
    expect((await api('GET', `/exports/${other}`, { token, now: T0 + 3 })).body.data.state).toBe('expired');
  });

  /** The raw response of a download with a Range header, so status and headers can be read. */
  const ranged = (path: string, range: string) =>
    api('GET', path, { token, extraHeaders: { range } });

  it('serves a byte range of an attachment, the tail, and refuses a range past the end', async () => {
    const body = 'attached note with some more words in it';
    const sourceId = await ingest(mail('Ranges', 'Body', body));
    await runPending('triage');
    const attachment = (await api('GET', `/sources/${sourceId}`, { token })).body.data.attachments[0] as { id: string; sizeBytes: number; sha256: string };
    const path = `/attachments/${attachment.id}/content`;
    const whole = await api('GET', path, { token });
    expect(whole.status).toBe(200);
    expect(whole.headers.get('accept-ranges')).toBe('bytes');
    const bytes = whole.body.data as Uint8Array;
    expect(bytes.length).toBe(attachment.sizeBytes);

    const middle = await ranged(path, 'bytes=2-9');
    expect(middle.status).toBe(206);
    expect(middle.headers.get('content-range')).toBe(`bytes 2-9/${bytes.length}`);
    expect(middle.headers.get('content-length')).toBe('8');
    expect(middle.body.data).toEqual(bytes.slice(2, 10));
    // The checksum is the whole file's, on a slice too, so a resumed download can still be checked at the end.
    expect(middle.headers.get('x-attachment-sha256')).toBe(attachment.sha256);

    expect((await ranged(path, 'bytes=5-')).body.data).toEqual(bytes.slice(5));
    expect((await ranged(path, 'bytes=-6')).body.data).toEqual(bytes.slice(-6));
    // A range that runs past the end is cut to the end.
    expect((await ranged(path, `bytes=${bytes.length - 3}-9999`)).body.data).toEqual(bytes.slice(-3));

    const past = await ranged(path, `bytes=${bytes.length}-`);
    expect(past.status).toBe(416);
    expect(past.headers.get('content-range')).toBe(`bytes */${bytes.length}`);
    // What this does not honour is answered in full: several ranges, another unit, nonsense, or a condition we cannot check.
    for (const range of ['bytes=0-1,4-5', 'items=0-1', 'bytes=abc', 'bytes=9-2', 'bytes=-']) {
      const res = await ranged(path, range);
      expect(res.status, range).toBe(200);
      expect(res.body.data).toEqual(bytes);
    }
    const conditional = await api('GET', path, { token, extraHeaders: { range: 'bytes=0-1', 'if-range': '"x"' } });
    expect(conditional.status).toBe(200);
  });

  it('serves a byte range of an export ZIP, so an interrupted download can resume', async () => {
    await ingest(mail('Resume me', 'Body', 'attached'));
    const id = await createExport();
    expect(await runPending('export', T0 + 2000)).toEqual(['complete']);
    const path = `/exports/${id}/content`;
    const whole = (await api('GET', path, { token })).body.data as Uint8Array;
    const head = await ranged(path, 'bytes=0-3');
    expect(head.status).toBe(206);
    expect(head.headers.get('content-range')).toBe(`bytes 0-3/${whole.length}`);
    expect(head.body.data).toEqual(whole.slice(0, 4));
    const rest = await ranged(path, 'bytes=4-');
    expect(rest.status).toBe(206);
    // The two pieces are the ZIP, byte for byte.
    expect(new Uint8Array([...(head.body.data as Uint8Array), ...(rest.body.data as Uint8Array)])).toEqual(whole);
    expect((await ranged(path, `bytes=${whole.length}-`)).status).toBe(416);
    // An invalid or expired export is still refused before any range is considered.
    expect((await ranged(`/exports/${crypto.randomUUID()}/content`, 'bytes=0-3')).status).toBe(404);
  });

  it('lists exports newest first, each as GET /exports/{id} reports it', async () => {
    await ingest(mail('One', 'Body'));
    const first = await createExport(T0);
    const second = await createExport(T0 + 5000);
    expect(await runPending('export', T0 + 6000)).toEqual(['complete', 'complete']);
    const listed = await api('GET', '/exports', { token, now: T0 + 7000 });
    expect(listed.status).toBe(200);
    expectContract('export-list', listed.body.data);
    expect(listed.body.data.exports.map((e: { id: string }) => e.id)).toEqual([second, first]);
    for (const entry of listed.body.data.exports) {
      expect(entry).toEqual((await api('GET', `/exports/${entry.id}`, { token, now: T0 + 7000 })).body.data);
    }
    expect((await api('GET', '/exports?limit=1', { token, now: T0 + 7000 })).body.data.exports).toHaveLength(1);
    for (const bad of ['limit=0', 'limit=51', 'limit=abc', 'cursor=x']) expect((await api('GET', `/exports?${bad}`, { token, now: T0 + 7000 })).status, bad).toBe(400);
    expect((await api('GET', '/exports', { now: T0 })).status).toBe(401);
  });

  it('lists an export whose manifest is gone as failed instead of failing the list', async () => {
    await ingest(mail('Broken', 'Body'));
    const id = await createExport();
    expect(await runPending('export', T0 + 2000)).toEqual(['complete']);
    const row = await env.CONTROL_DB.prepare('SELECT manifest_key FROM exports WHERE id = ?').bind(id).first<{ manifest_key: string }>();
    await env.ARCHIVE_BUCKET.delete(row!.manifest_key);
    const single = await api('GET', `/exports/${id}`, { token, now: T0 + 3000 });
    expect(single.status).toBe(500);
    const listed = await api('GET', '/exports', { token, now: T0 + 3000 });
    expect(listed.status).toBe(200);
    expectContract('export-list', listed.body.data);
    expect(listed.body.data.exports[0]).toMatchObject({ id, state: 'failed', failureCode: 'export_manifest_unavailable', counts: null, omissions: null });
  });

  it('requires a session', async () => {
    expect((await api('POST', '/exports', { key: key(), body: {} })).status).toBe(401);
    expect((await api('GET', `/exports/${crypto.randomUUID()}`, { token })).status).toBe(404);
  });
});
