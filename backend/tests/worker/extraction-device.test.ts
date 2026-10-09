import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { EXTRACTION_LEASE_MS, MAX_BATCH_TEXT_BYTES, UNPUBLISHED_GENERATION_MS, maintainExtraction } from '../../src/extraction/device';
import { sha256Hex } from '../../src/ids';
import { DEFAULT_INFERENCE, type InferenceSettings, loadSettings } from '../../src/inference/settings';
import { T0, api, contractValidator, enrollOwner, ingest, runPending, sessionClock } from './support';

let token: string;
let tokenAt: (now: number) => Promise<string>;
let keyCount = 0;
const key = () => `00000000-0000-4000-b100-${String((keyCount += 1)).padStart(12, '0')}`;
const db = () => env.CONTROL_DB;
const encoder = new TextEncoder();

const base64 = (data: Uint8Array) => {
  let binary = '';
  for (let i = 0; i < data.length; i += 0x8000) binary += String.fromCharCode(...data.subarray(i, i + 0x8000));
  return btoa(binary).replace(/.{76}/g, '$&\r\n');
};

const mail = (files: { name: string; type: string; data: Uint8Array }[], subject = 'Scans') =>
  [
    'From: scanner@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${subject}`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain; charset=utf-8',
    '',
    'Scans attached.',
    ...files.flatMap((f) => ['--b1', `Content-Type: ${f.type}; name="${f.name}"`, `Content-Disposition: attachment; filename="${f.name}"`, 'Content-Transfer-Encoding: base64', '', base64(f.data)]),
    '--b1--',
    '',
  ].join('\r\n');

const pdf = (marker = 'a') => ({ name: `scan-${marker}.pdf`, type: 'application/pdf', data: encoder.encode(`%PDF-1.7\n%${marker}-binary`) });
const png = (marker = 'a') => ({ name: `photo-${marker}.png`, type: 'image/png', data: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, ...encoder.encode(marker)]) });

async function deferredSource(files = [pdf()], subject = 'Scans') {
  const sourceId = await ingest(mail(files, subject));
  await runPending('extract');
  const attachments = (await api('GET', `/sources/${sourceId}`, { token })).body.data.attachments as { id: string; name: string }[];
  return { sourceId, attachments: Object.fromEntries(attachments.map((a) => [a.name, a.id])) as Record<string, string> };
}

/** Requests and answers are judged by the shared schemas, the same ones the phone's tests use. */
async function claim(o: { pdfText?: boolean; ocr?: boolean; now?: number } = {}) {
  const body = { extractorVersion: 'android-extract-v1', pdfText: o.pdfText ?? true, ocr: o.ocr ?? true };
  expect(contractValidator('extraction-claim-request')(body)).toBe(true);
  const res = await api('POST', '/device-work/extraction/claim', { token, key: key(), now: o.now ?? T0, body });
  if (res.status === 200) expect(contractValidator('extraction-claim')(res.body.data), JSON.stringify(res.body.data)).toBe(true);
  return res;
}

interface Work {
  leaseId: string;
  leaseToken: string;
  attachmentId: string;
  sourceId: string;
  kind: string;
  contentHash: string;
  extractorVersion: string;
  policyRevision: number;
  generationId: string;
  ocrAllowed: boolean;
  startPage: number;
  nextBatchOrdinal: number;
  nextPage: number;
  batchHashes: string[];
}

async function claimWork(o: { pdfText?: boolean; ocr?: boolean; now?: number } = {}): Promise<Work> {
  const res = await claim(o);
  expect(res.status).toBe(200);
  expect(res.body.data.work, JSON.stringify(res.body.data)).not.toBeNull();
  return res.body.data.work;
}

const page = (p: number, text: string, extra: object = {}) => ({ page: p, part: 0, method: 'pdf_text', text, ...extra });

async function batch(work: Work, over: Record<string, unknown> = {}, now = T0) {
  const body = {
    leaseToken: work.leaseToken,
    contentHash: work.contentHash,
    extractorVersion: work.extractorVersion,
    policyRevision: work.policyRevision,
    generationId: work.generationId,
    batchOrdinal: 0,
    pages: [page(1, 'Invoice 7731 is due on Friday.')],
    lastPage: 1,
    complete: false,
    ...over,
  };
  const res = await api('POST', `/device-work/extraction/${work.leaseId}/batch`, { token, key: key(), now, body });
  // Whatever the Worker accepts must satisfy the shared request schema, and its answer the shared response schema.
  if (res.status === 200) {
    expect(contractValidator('extraction-batch')(body), JSON.stringify(body)).toBe(true);
    expect(contractValidator('extraction-batch-result')(res.body.data), JSON.stringify(res.body.data)).toBe(true);
  }
  return res;
}

/** What the phone sends with its last batch: a digest of the server's own batch hashes, in order. */
const manifest = (hashes: string[]) => sha256Hex(hashes.join('\n'));

const state = async (attachmentId: string) => db().prepare('SELECT state, detail, active_generation, revision FROM attachment_processing WHERE attachment_id = ?').bind(attachmentId).first<{ state: string; detail: string | null; active_generation: string | null; revision: number }>();
const chunksOf = async (sourceId: string) => (await api('GET', `/sources/${sourceId}/chunks`, { token })).body.data.chunks as { attachmentId: string | null; page: number | null; text: string; startOffset: number | null }[];
const found = async (q: string) => (await api('GET', `/search?q=${encodeURIComponent(q)}`, { token })).body.data.results as { sourceId: string }[];
/** The distinct emails a search finds: a hit is one chunk, so a long document matches many times. */
const foundIn = async (q: string) => [...new Set((await found(q)).map((r) => r.sourceId))];

async function setInference(inference: InferenceSettings) {
  const current = await loadSettings(db());
  expect((await api('PATCH', '/settings', { token, key: key(), body: { expectedRevision: current.revision, inference } })).status).toBe(200);
}

beforeEach(async () => {
  const owner = await enrollOwner();
  token = owner.token;
  tokenAt = sessionClock(owner);
});

describe('device extraction', () => {
  it('defers PDFs and images with their kind and leaves other files out of the queue', async () => {
    const { attachments } = await deferredSource([pdf('k'), png('k'), { name: 'voice.mp3', type: 'audio/mpeg', data: encoder.encode('ID3\u0004binary') }]);
    const rows = await db().prepare('SELECT attachment_id, state, detail, device_kind FROM attachment_processing').all<{ attachment_id: string; state: string; detail: string | null; device_kind: string | null }>();
    const byId = Object.fromEntries(rows.results.map((r) => [r.attachment_id, r]));
    expect(byId[attachments['scan-k.pdf']!]).toMatchObject({ state: 'deferred', detail: 'device_unavailable', device_kind: 'pdf' });
    expect(byId[attachments['photo-k.png']!]).toMatchObject({ state: 'deferred', detail: 'device_unavailable', device_kind: 'image' });
    expect(byId[attachments['voice.mp3']!]).toMatchObject({ state: 'unsupported', device_kind: null });
  });

  it('leases a deferred PDF, shows it extracting, and gives each phone one file at a time', async () => {
    const { attachments, sourceId } = await deferredSource([pdf('l'), pdf('m')]);
    const work = await claimWork();
    expect(work).toMatchObject({ kind: 'pdf', sourceId, nextBatchOrdinal: 0, nextPage: 1, batchHashes: [], ocrAllowed: true, maxPages: 100, maxBatchPages: 10, maxBatchBytes: MAX_BATCH_TEXT_BYTES });
    expect(Object.values(attachments)).toContain(work.attachmentId);
    expect(await state(work.attachmentId)).toMatchObject({ state: 'extracting', detail: null, active_generation: null });
    expect((await claim()).body.data).toEqual({ work: null, reason: 'busy' });
    const change = await db().prepare("SELECT projection_json FROM changes WHERE entity_type = 'attachment' AND entity_id = ? ORDER BY seq DESC LIMIT 1").bind(work.attachmentId).first<{ projection_json: string }>();
    expect(JSON.parse(change!.projection_json)).toMatchObject({ id: work.attachmentId, sourceId, state: 'extracting', detail: null });
  });

  it('offers a phone only what it can do', async () => {
    await deferredSource([png('c')]);
    expect((await claim({ pdfText: true, ocr: false })).body.data).toEqual({ work: null, reason: 'no_work' });
    expect((await claim({ pdfText: false, ocr: false })).body.data).toEqual({ work: null, reason: 'no_capability' });
    expect((await claimWork({ pdfText: false, ocr: true })).kind).toBe('image');
  });

  it('publishes text only when the last batch commits, with pages, methods and offsets', async () => {
    const { sourceId } = await deferredSource();
    const work = await claimWork();
    const first = await batch(work, {
      pages: [page(1, 'Invoice 7731 is due on Friday.'), page(2, 'Handwritten note: pay', { method: 'ocr', confidence: 0.82 }), { page: 2, part: 1, method: 'ocr', text: 'the courier' }],
      lastPage: 2,
    });
    expect(first.status).toBe(200);
    expect(first.body.data).toMatchObject({ status: 'accepted', batchOrdinal: 0, nextBatchOrdinal: 1, nextPage: 3, published: false });
    expect(first.body.data.batchHash).toMatch(/^[0-9a-f]{64}$/);
    // Unpublished text is invisible everywhere.
    expect(await found('courier')).toHaveLength(0);
    expect((await chunksOf(sourceId)).filter((c) => c.attachmentId !== null)).toEqual([]);
    expect(await state(work.attachmentId)).toMatchObject({ state: 'extracting', active_generation: null });

    // The final batch carries a digest of the batches before it, as the server reported them.
    const wrong = await batch(work, { batchOrdinal: 1, pages: [page(3, 'Total 1,420.00 EUR')], lastPage: 3, complete: true, totalPages: 3, manifestHash: await manifest(['0'.repeat(64)]) });
    expect(wrong.status).toBe(409);
    expect(wrong.body.error?.code).toBe('manifest_mismatch');
    expect(await state(work.attachmentId)).toMatchObject({ state: 'extracting', active_generation: null });

    const done = await batch(work, { batchOrdinal: 1, pages: [page(3, 'Total 1,420.00 EUR')], lastPage: 3, complete: true, totalPages: 3, manifestHash: await manifest([first.body.data.batchHash]) });
    expect(done.status).toBe(200);
    expect(done.body.data).toMatchObject({ status: 'accepted', published: true, attachment: { state: 'ready', detail: null } });
    expect(await state(work.attachmentId)).toMatchObject({ state: 'ready', detail: null, active_generation: work.generationId });

    expect((await found('courier')).map((r) => r.sourceId)).toEqual([sourceId]);
    expect(await found('1,420')).toHaveLength(1);
    const chunks = (await chunksOf(sourceId)).filter((c) => c.attachmentId === work.attachmentId);
    expect(chunks.map((c) => [c.page, c.text, c.startOffset])).toEqual([
      [1, 'Invoice 7731 is due on Friday.', 0],
      [2, 'Handwritten note: pay', 0],
      // The next part of a page starts after the first part and the newline that joins them.
      [2, 'the courier', 22],
      [3, 'Total 1,420.00 EUR', 0],
    ]);
    const methods = await env.ARCHIVE_0001.prepare('SELECT page, method FROM chunks WHERE attachment_id = ? ORDER BY ordinal').bind(work.attachmentId).all<{ page: number; method: string }>();
    expect(methods.results.map((r) => `${r.page}:${r.method}`)).toEqual(['1:pdf_text', '2:ocr', '2:ocr', '3:pdf_text']);
    expect(await db().prepare("SELECT state FROM extraction_leases WHERE id = ?").bind(work.leaseId).first()).toEqual({ state: 'published' });
    const change = await db().prepare("SELECT projection_json FROM changes WHERE entity_type = 'attachment' AND entity_id = ? ORDER BY seq DESC LIMIT 1").bind(work.attachmentId).first<{ projection_json: string }>();
    expect(JSON.parse(change!.projection_json)).toMatchObject({ state: 'ready', detail: null });
    // The original is untouched and the file is not offered again.
    expect((await api('GET', `/attachments/${work.attachmentId}/content`, { token })).status).toBe(200);
    expect((await claim()).body.data).toEqual({ work: null, reason: 'no_work' });
  });

  it('answers a repeated batch the same way, and refuses different text under the same number', async () => {
    await deferredSource();
    const work = await claimWork();
    const first = await batch(work);
    const again = await batch(work);
    expect(again.status).toBe(200);
    expect(again.body.data).toMatchObject({ status: 'replayed', batchHash: first.body.data.batchHash, nextBatchOrdinal: 1, nextPage: 2 });
    expect((await db().prepare('SELECT COUNT(*) AS n FROM extraction_batches').first<{ n: number }>())!.n).toBe(1);

    const conflict = await batch(work, { pages: [page(1, 'Something else entirely.')] });
    expect(conflict.status).toBe(409);
    expect(conflict.body.error?.code).toBe('batch_conflict');

    const skipped = await batch(work, { batchOrdinal: 5, pages: [page(2, 'later')], lastPage: 2 });
    expect(skipped.status).toBe(409);
    expect(skipped.body.error).toMatchObject({ code: 'batch_out_of_order', details: { expectedBatchOrdinal: 1 } });
  });

  it('refuses batches whose pages or size break the rules', async () => {
    await deferredSource();
    const work = await claimWork();
    const refused = async (over: Record<string, unknown>, reason: string) => {
      const res = await batch(work, over);
      expect(res.status, JSON.stringify(res.body)).toBe(400);
      expect(res.body.error?.details?.reason ?? res.body.error?.code).toBe(reason);
    };
    await refused({ pages: [page(1, 'a'), page(1, 'b')] }, 'order');
    await refused({ pages: [page(2, 'b'), page(1, 'a')], lastPage: 2 }, 'order');
    await refused({ pages: [{ ...page(1, 'a'), part: 1 }] }, 'order');
    await refused({ pages: [page(2, 'beyond the last page')], lastPage: 1 }, 'out_of_range');
    await refused({ lastPage: 12 }, 'too_many_pages');
    await refused({ pages: [page(1, 'x'.repeat(70_000)), page(2, 'y'.repeat(70_000))], lastPage: 2 }, 'too_much_text');
    await refused({ complete: true }, 'required');
    await refused({ manifestHash: '0'.repeat(64) }, 'only_when_complete');
    await refused({ totalPages: 3 }, 'only_when_complete');
    await refused({ extra: true }, 'unknown_field');
    expect((await db().prepare('SELECT COUNT(*) AS n FROM extraction_batches').first<{ n: number }>())!.n).toBe(0);
    // Nothing a refused batch carried reached the archive.
    expect((await env.ARCHIVE_0001.prepare("SELECT COUNT(*) AS n FROM chunks WHERE generation_id LIKE 'dev:%'").first<{ n: number }>())!.n).toBe(0);
  });

  it('requires the final batch at the page limit and records a longer document as limited', async () => {
    const { sourceId } = await deferredSource();
    const work = await claimWork();
    const hashes: string[] = [];
    for (let ordinal = 0; ordinal < 9; ordinal += 1) {
      const lastPage = (ordinal + 1) * 10;
      const res = await batch(work, { batchOrdinal: ordinal, pages: [page(lastPage - 9, `Page ${lastPage - 9}`)], lastPage });
      expect(res.status, JSON.stringify(res.body)).toBe(200);
      hashes.push(res.body.data.batchHash);
    }
    const open = await batch(work, { batchOrdinal: 9, pages: [page(91, 'Page 91')], lastPage: 100 });
    expect(open.status).toBe(400);
    expect(open.body.error?.details?.reason).toBe('required_at_page_limit');
    const done = await batch(work, { batchOrdinal: 9, pages: [page(91, 'Page 91')], lastPage: 100, complete: true, totalPages: 250, manifestHash: await manifest(hashes) });
    expect(done.status, JSON.stringify(done.body)).toBe(200);
    expect(done.body.data.attachment).toEqual({ state: 'ready', detail: 'limit' });
    expect((await chunksOf(sourceId)).filter((c) => c.attachmentId === work.attachmentId)).toHaveLength(10);
  });

  /** Reads pages 1 to 100 of a 250-page document (one short page of text each) and publishes them with the limit. */
  async function publishFirstHundred(files = [pdf()]) {
    const source = await deferredSource(files);
    const work = await claimWork();
    const hashes: string[] = [];
    for (let ordinal = 0; ordinal < 10; ordinal += 1) {
      const first = ordinal * 10 + 1;
      const pages = Array.from({ length: 10 }, (_, i) => page(first + i, `Alpha page ${first + i} text`));
      const last = first + 9;
      const final = ordinal === 9;
      const res = await batch(work, { batchOrdinal: ordinal, pages, lastPage: last, ...(final ? { complete: true, totalPages: 250, manifestHash: await manifest(hashes) } : {}) });
      expect(res.status, JSON.stringify(res.body)).toBe(200);
      hashes.push(res.body.data.batchHash);
    }
    return { ...source, work, hashes };
  }

  const readMore = (attachmentId: string, now = T0) => api('POST', `/attachments/${attachmentId}/read-more`, { token, key: key(), now, body: {} });
  const archiveCount = async (generation: string) => (await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM chunks WHERE generation_id = ?').bind(generation).first<{ n: number }>())!.n;

  it('reads on past the limit: the published text stays searchable, the new generation starts as its copy and replaces it whole', async () => {
    const { sourceId, work: first } = await publishFirstHundred();
    expect(await state(first.attachmentId)).toMatchObject({ state: 'ready', detail: 'limit', active_generation: first.generationId });
    expect(await foundIn('Alpha')).toEqual([sourceId]);

    const asked = await readMore(first.attachmentId);
    expect(asked.status, JSON.stringify(asked.body)).toBe(200);
    expect(asked.body.data).toMatchObject({ state: 'deferred', detail: 'device_unavailable', resumePage: 101 });
    // Waiting for the phone changes nothing the owner can search.
    expect(await foundIn('Alpha')).toEqual([sourceId]);
    expect(await state(first.attachmentId)).toMatchObject({ state: 'deferred', active_generation: first.generationId });

    const work = await claimWork({ now: T0 + 1000 });
    expect(work).toMatchObject({ attachmentId: first.attachmentId, startPage: 101, nextBatchOrdinal: 10, nextPage: 101 });
    expect(work.batchHashes).toHaveLength(10);
    expect(work.generationId).toBe(`${first.generationId}:r101`);
    // The copy is whole before the lease exists, and the published generation is untouched.
    expect(await archiveCount(work.generationId)).toBe(await archiveCount(first.generationId));
    expect(await state(first.attachmentId)).toMatchObject({ state: 'extracting', active_generation: first.generationId });

    const b10 = await batch(work, { batchOrdinal: 10, pages: [page(101, 'Beta page 101 text'), page(105, 'Beta page 105 text')], lastPage: 110 });
    expect(b10.status, JSON.stringify(b10.body)).toBe(200);
    expect(await foundIn('Beta')).toEqual([]);
    const done = await batch(work, { batchOrdinal: 11, pages: [page(111, 'Beta page 111 text')], lastPage: 120, complete: true, totalPages: 120, manifestHash: await manifest([...work.batchHashes, b10.body.data.batchHash]) });
    expect(done.status, JSON.stringify(done.body)).toBe(200);
    expect(done.body.data.attachment).toEqual({ state: 'ready', detail: null });

    expect(await state(first.attachmentId)).toMatchObject({ state: 'ready', detail: null, active_generation: work.generationId });
    const row = await db().prepare('SELECT pages_read, pages_total, resume_page FROM attachment_processing WHERE attachment_id = ?').bind(first.attachmentId).first();
    expect(row).toEqual({ pages_read: 120, pages_total: 120, resume_page: null });
    // Old and new text are both there, each once: only the new generation is read.
    expect(await foundIn('Alpha')).toEqual([sourceId]);
    expect(await foundIn('Beta')).toEqual([sourceId]);
    const published = await env.ARCHIVE_0001.prepare('SELECT page FROM chunks WHERE attachment_id = ? AND generation_id = ? ORDER BY ordinal').bind(first.attachmentId, work.generationId).all<{ page: number }>();
    expect(published.results.map((c) => c.page)).toEqual([...Array.from({ length: 100 }, (_, i) => i + 1), 101, 105, 111]);
    // The earlier generation goes after a day, and the new one stays.
    await maintainExtraction(env, T0 + UNPUBLISHED_GENERATION_MS + 10_000);
    expect(await archiveCount(first.generationId)).toBe(0);
    expect(await archiveCount(work.generationId)).toBe(103);
    expect(await foundIn('Alpha')).toEqual([sourceId]);
  });

  it('stops a lease a hundred pages after the page it started on, and makes the last of them close the read', async () => {
    const { work: first } = await publishFirstHundred();
    await readMore(first.attachmentId);
    const work = await claimWork({ now: T0 + 1000 });
    // Pages 101 to 190 in nine batches, then 191 to 194: the next batch stays within ten pages but would end past page 200.
    for (let ordinal = 10; ordinal < 19; ordinal += 1) {
      const firstPage = 101 + (ordinal - 10) * 10;
      const res = await batch(work, { batchOrdinal: ordinal, pages: [page(firstPage, `Beta page ${firstPage} text`)], lastPage: firstPage + 9 }, T0 + 1000);
      expect(res.status, JSON.stringify(res.body)).toBe(200);
    }
    expect((await batch(work, { batchOrdinal: 19, pages: [page(191, 'Beta page 191 text')], lastPage: 194 }, T0 + 1000)).status).toBe(200);
    const over = await batch(work, { batchOrdinal: 20, pages: [page(195, 'Beta page 195 text')], lastPage: 204 }, T0 + 1000);
    expect(over.status).toBe(400);
    expect(over.body.error?.details?.reason).toBe('beyond_lease_pages');
    // Page 200 is the last this lease may read, so a batch ending there must close the read.
    const open = await batch(work, { batchOrdinal: 20, pages: [page(195, 'Beta page 195 text')], lastPage: 200 }, T0 + 1000);
    expect(open.status).toBe(400);
    expect(open.body.error?.details?.reason).toBe('required_at_page_limit');
  });

  it('keeps reading in steps: a second request after another limit continues from the page reached', async () => {
    const { work: first } = await publishFirstHundred();
    await readMore(first.attachmentId);
    const second = await claimWork({ now: T0 + 1000 });
    const hashes = [...second.batchHashes];
    for (let ordinal = 10; ordinal < 20; ordinal += 1) {
      const firstPage = 101 + (ordinal - 10) * 10;
      const final = ordinal === 19;
      const res = await batch(second, { batchOrdinal: ordinal, pages: [page(firstPage, `Beta page ${firstPage} text`)], lastPage: firstPage + 9, ...(final ? { complete: true, totalPages: 250, manifestHash: await manifest(hashes) } : {}) }, T0 + 1000);
      expect(res.status, JSON.stringify(res.body)).toBe(200);
      hashes.push(res.body.data.batchHash);
    }
    expect(await state(first.attachmentId)).toMatchObject({ state: 'ready', detail: 'limit' });
    const asked = await readMore(first.attachmentId, T0 + 2000);
    expect(asked.body.data.resumePage).toBe(201);
    const third = await claimWork({ now: T0 + 3000 });
    expect(third).toMatchObject({ startPage: 201, nextBatchOrdinal: 20, nextPage: 201 });
    expect(third.generationId).toBe(`${first.generationId}:r201`);
  });

  it('refuses to read more when there is nothing to read or the email may not be processed', async () => {
    const fresh = await publishFirstHundred([pdf('y')]);
    // A file that was never read, and one that does not exist.
    const other = await deferredSource([pdf('z')]);
    expect((await readMore(other.attachments['scan-z.pdf']!)).body.error?.code).toBe('nothing_to_read');
    expect((await readMore(crypto.randomUUID())).status).toBe(404);
    const revision = (await api('GET', `/sources/${fresh.sourceId}`, { token })).body.data.source.revision as number;
    expect((await api('POST', `/sources/${fresh.sourceId}/quarantine`, { token, key: key(), body: { expectedRevision: revision } })).status).toBe(200);
    expect((await readMore(fresh.work.attachmentId)).body.error?.code).toBe('not_eligible');
  });

  it('keeps the published text and the offer to read more when a resumed read fails or is handed back', async () => {
    const { work: first, sourceId } = await publishFirstHundred();
    await readMore(first.attachmentId);
    const resumed = await claimWork({ now: T0 + 1000 });
    const failed = await api('POST', `/device-work/extraction/${resumed.leaseId}/outcome`, { token, key: key(), now: T0 + 1000, body: { leaseToken: resumed.leaseToken, outcome: 'failed' } });
    expect(failed.body.data.attachment).toEqual({ state: 'ready', detail: 'limit' });
    expect(await state(first.attachmentId)).toMatchObject({ state: 'ready', detail: 'limit', active_generation: first.generationId });
    expect(await db().prepare('SELECT resume_page FROM attachment_processing WHERE attachment_id = ?').bind(first.attachmentId).first()).toEqual({ resume_page: null });
    expect(await foundIn('Alpha')).toEqual([sourceId]);

    // Handing it back for later keeps the request: the file waits and is claimed again from the same page.
    await readMore(first.attachmentId, T0 + 2000);
    const again = await claimWork({ now: T0 + 3000 });
    await api('POST', `/device-work/extraction/${again.leaseId}/outcome`, { token, key: key(), now: T0 + 3000, body: { leaseToken: again.leaseToken, outcome: 'unavailable' } });
    expect(await state(first.attachmentId)).toMatchObject({ state: 'deferred', detail: 'device_unavailable' });
    const third = await claimWork({ now: T0 + 4000 });
    expect(third).toMatchObject({ startPage: 101, nextBatchOrdinal: 10 });
  });

  it('continues an expired resumed read without copying again or duplicating text', async () => {
    const { work: first, sourceId } = await publishFirstHundred();
    await readMore(first.attachmentId);
    const old = await claimWork({ now: T0 + 1000 });
    const b10 = await batch(old, { batchOrdinal: 10, pages: [page(101, 'Beta page 101 text')], lastPage: 110 }, T0 + 1000);
    expect(b10.status).toBe(200);
    const later = T0 + 1000 + EXTRACTION_LEASE_MS + 60_000;
    token = await tokenAt(later);
    const fresh = await claimWork({ now: later });
    expect(fresh).toMatchObject({ generationId: old.generationId, startPage: 101, nextBatchOrdinal: 11, nextPage: 111 });
    expect(fresh.batchHashes).toHaveLength(11);
    expect(await archiveCount(old.generationId)).toBe(101);
    expect(await foundIn('Alpha')).toEqual([sourceId]);
  });

  it('keeps the lease alive with heartbeats and returns the file when it expires', async () => {
    await deferredSource();
    const work = await claimWork();
    const beat = await api('POST', `/device-work/extraction/${work.leaseId}/heartbeat`, { token, key: key(), now: T0 + 9 * 60_000, body: { leaseToken: work.leaseToken } });
    expect(beat.body.data.expiresAt).toBe(new Date(T0 + 9 * 60_000 + EXTRACTION_LEASE_MS).toISOString());
    // Still held at the original expiry because of the heartbeat.
    expect((await claim({ now: T0 + 12 * 60_000 })).body.data).toEqual({ work: null, reason: 'busy' });
    const wrong = await api('POST', `/device-work/extraction/${work.leaseId}/heartbeat`, { token, key: key(), now: T0 + 9 * 60_000, body: { leaseToken: crypto.randomUUID() } });
    expect(wrong.status).toBe(409);
    expect(wrong.body.error?.code).toBe('lease_lost');

    const later = T0 + 9 * 60_000 + EXTRACTION_LEASE_MS + 1000;
    token = await tokenAt(later);
    await expireAt(later);
    expect(await state(work.attachmentId)).toMatchObject({ state: 'deferred', detail: 'device_unavailable' });
    expect((await batch(work, {}, later)).body.error?.code).toBe('lease_lost');
  });

  it('continues a run on a new lease after the first one expired, from the batches already stored', async () => {
    const { sourceId } = await deferredSource();
    const old = await claimWork();
    const first = await batch(old, { pages: [page(1, 'Page one survives a lost lease.')] });
    const later = T0 + EXTRACTION_LEASE_MS + 60_000;
    const fresh = await claimWork({ now: later });
    expect(fresh.leaseId).not.toBe(old.leaseId);
    expect(fresh).toMatchObject({ attachmentId: old.attachmentId, generationId: old.generationId, nextBatchOrdinal: 1, nextPage: 2, batchHashes: [first.body.data.batchHash] });
    // The old lease cannot add or publish anything any more.
    expect((await batch(old, { batchOrdinal: 1, pages: [page(2, 'stale')], lastPage: 2 }, later)).body.error?.code).toBe('lease_lost');
    const done = await batch(fresh, { batchOrdinal: 1, pages: [page(2, 'Page two comes from the new lease.')], lastPage: 2, complete: true, totalPages: 2, manifestHash: await manifest(fresh.batchHashes) }, later);
    expect(done.status, JSON.stringify(done.body)).toBe(200);
    expect((await chunksOf(sourceId)).filter((c) => c.attachmentId === old.attachmentId).map((c) => c.text)).toEqual(['Page one survives a lost lease.', 'Page two comes from the new lease.']);
  });

  it('stops a lease when the email is quarantined, and offers the file again after release', async () => {
    const { sourceId } = await deferredSource();
    const work = await claimWork();
    await batch(work);
    const revision = (await api('GET', `/sources/${sourceId}`, { token })).body.data.source.revision as number;
    const quarantined = await api('POST', `/sources/${sourceId}/quarantine`, { token, key: key(), body: { expectedRevision: revision } });
    expect(quarantined.status, JSON.stringify(quarantined.body)).toBe(200);
    // The lease ended with the quarantine, and the file is back in the queue though nobody has sent another batch.
    expect(await db().prepare('SELECT state FROM extraction_leases WHERE id = ?').bind(work.leaseId).first()).toEqual({ state: 'cancelled' });
    expect(await state(work.attachmentId)).toMatchObject({ state: 'deferred', detail: 'device_unavailable' });
    const late = await batch(work, { batchOrdinal: 1, pages: [page(2, 'after quarantine')], lastPage: 2 });
    expect(late.status).toBe(409);
    expect(['lease_lost', 'not_eligible']).toContain(late.body.error?.code);
    expect((await claim()).body.data).toEqual({ work: null, reason: 'no_work' });
    expect(await found('quarantine')).toHaveLength(0);

    expect((await api('POST', `/sources/${sourceId}/release`, { token, key: key(), body: { expectedRevision: revision + 1 } })).status).toBe(200);
    const again = await claimWork();
    expect(again.attachmentId).toBe(work.attachmentId);
    expect(again.leaseId).not.toBe(work.leaseId);
  });

  it('refuses a batch for an email being deleted and removes lease and batch records with the email', async () => {
    const { sourceId } = await deferredSource();
    const work = await claimWork();
    await batch(work);
    const planned = await api('POST', '/reviews', { token, key: key(), body: { effect: 'archive.delete', params: { sourceIds: [sourceId] } } });
    expect(planned.status).toBe(200);
    const confirmed = await api('POST', `/reviews/${planned.body.data.reviewId}/confirm`, { token, key: key(), body: { paramsDigest: planned.body.data.paramsDigest } });
    expect(confirmed.status).toBe(200);
    const late = await batch(work, { batchOrdinal: 1, pages: [page(2, 'too late')], lastPage: 2 });
    expect(late.status).toBe(410);
    expect(late.body.error?.code).toBe('deleted');
    expect(await runPending('cleanup')).toEqual(['complete']);
    expect(await db().prepare('SELECT COUNT(*) AS n FROM extraction_leases WHERE source_id = ?').bind(sourceId).first()).toEqual({ n: 0 });
    expect(await db().prepare('SELECT COUNT(*) AS n FROM extraction_batches WHERE source_id = ?').bind(sourceId).first()).toEqual({ n: 0 });
    expect(await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM chunks WHERE source_id = ?').bind(sourceId).first()).toEqual({ n: 0 });
  });

  it('ends the lease when inference settings change, and allows text but not recognition under no-AI', async () => {
    const { sourceId } = await deferredSource([pdf('n'), png('n')]);
    const work = await claimWork({ pdfText: true, ocr: true });
    await batch(work);
    await setInference({ ...DEFAULT_INFERENCE, mode: 'no_ai' });
    const stale = await batch(work, { batchOrdinal: 1, pages: [page(2, 'old policy')], lastPage: 2 });
    expect(stale.status).toBe(409);
    expect(stale.body.error?.code).toBe('policy_changed');
    expect(await state(work.attachmentId)).toMatchObject({ state: 'deferred', detail: 'device_unavailable' });

    // Images need recognition, so only the PDF is offered, and only for its own text.
    expect((await claim({ pdfText: false, ocr: true })).body.data).toEqual({ work: null, reason: 'no_work' });
    const text = await claimWork({ pdfText: true, ocr: true });
    expect(text).toMatchObject({ kind: 'pdf', ocrAllowed: false, sourceId });
    expect(text.generationId).not.toBe(work.generationId);
    const ocr = await batch(text, { pages: [page(1, 'scanned', { method: 'ocr' })] });
    expect(ocr.status).toBe(409);
    expect(ocr.body.error?.code).toBe('ocr_not_allowed');
    expect((await batch(text, { pages: [page(1, 'native text layer')], lastPage: 1 })).status).toBe(200);
  });

  it('refuses to publish text the archive does not match', async () => {
    const { sourceId } = await deferredSource();
    const work = await claimWork();
    const first = await batch(work);
    await env.ARCHIVE_0001.prepare("UPDATE chunks SET text_hash = 'tampered' WHERE attachment_id = ? AND generation_id = ?").bind(work.attachmentId, work.generationId).run();
    const done = await batch(work, { batchOrdinal: 1, pages: [page(2, 'Second page')], lastPage: 2, complete: true, totalPages: 2, manifestHash: await manifest([first.body.data.batchHash]) });
    expect(done.status).toBe(409);
    expect(done.body.error?.code).toBe('generation_corrupt');
    expect(await state(work.attachmentId)).toMatchObject({ state: 'deferred', active_generation: null });
    expect(await db().prepare('SELECT COUNT(*) AS n FROM extraction_batches').first()).toEqual({ n: 0 });
    expect(await env.ARCHIVE_0001.prepare("SELECT COUNT(*) AS n FROM chunks WHERE source_id = ? AND generation_id LIKE 'dev:%'").bind(sourceId).first()).toEqual({ n: 0 });
    expect(await found('Second')).toHaveLength(0);
  });

  it('records a failure or a hand-back from the phone', async () => {
    const { attachments } = await deferredSource([pdf('o'), pdf('p')]);
    const first = await claimWork();
    const wrongToken = await api('POST', `/device-work/extraction/${first.leaseId}/outcome`, { token, key: key(), body: { leaseToken: crypto.randomUUID(), outcome: 'failed' } });
    expect(wrongToken.status).toBe(409);
    const gaveBack = await api('POST', `/device-work/extraction/${first.leaseId}/outcome`, { token, key: key(), body: { leaseToken: first.leaseToken, outcome: 'unavailable' } });
    expect(gaveBack.body.data.attachment).toEqual({ state: 'deferred', detail: 'device_unavailable' });
    expect((await batch(first)).body.error?.code).toBe('lease_lost');

    // The file that was handed back is the first one offered again.
    const second = await claimWork();
    expect(second.attachmentId).toBe(first.attachmentId);
    const encrypted = await api('POST', `/device-work/extraction/${second.leaseId}/outcome`, { token, key: key(), body: { leaseToken: second.leaseToken, outcome: 'encrypted' } });
    expect(encrypted.body.data.attachment).toEqual({ state: 'unsupported', detail: 'encrypted' });
    expect(await state(second.attachmentId)).toMatchObject({ state: 'unsupported', detail: 'encrypted' });
    const rest = await claimWork();
    expect(Object.values(attachments)).toContain(rest.attachmentId);
    expect(rest.attachmentId).not.toBe(second.attachmentId);
    const failed = await api('POST', `/device-work/extraction/${rest.leaseId}/outcome`, { token, key: key(), body: { leaseToken: rest.leaseToken, outcome: 'failed' } });
    expect(failed.body.data.attachment).toEqual({ state: 'failed', detail: 'parser_error' });
  });

  it('removes generations that were never published after a day and keeps the published one', async () => {
    const { sourceId } = await deferredSource();
    const abandoned = await claimWork();
    await batch(abandoned, { pages: [page(1, 'abandoned words')] });
    // A policy change makes the next run a new generation; the first one is never published.
    await setInference({ ...DEFAULT_INFERENCE, mode: 'local_only' });
    const later = T0 + EXTRACTION_LEASE_MS + 1000;
    const kept = await claimWork({ now: later });
    expect(kept.attachmentId).toBe(abandoned.attachmentId);
    expect(kept.generationId).not.toBe(abandoned.generationId);
    expect(kept).toMatchObject({ nextBatchOrdinal: 0, nextPage: 1, batchHashes: [] });
    const done = await batch(kept, { pages: [page(1, 'published words')], lastPage: 1, complete: true, totalPages: 1, manifestHash: await manifest([]) }, later);
    expect(done.status, JSON.stringify(done.body)).toBe(200);

    const count = async (generation: string) => (await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM chunks WHERE source_id = ? AND generation_id = ?').bind(sourceId, generation).first<{ n: number }>())!.n;
    await maintainExtraction(env, later + 1000);
    expect(await count(abandoned.generationId)).toBe(1);
    await maintainExtraction(env, T0 + UNPUBLISHED_GENERATION_MS + 1000);
    expect(await count(abandoned.generationId)).toBe(0);
    expect(await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM source_fts WHERE generation_id = ?').bind(abandoned.generationId).first()).toEqual({ n: 0 });
    expect(await db().prepare('SELECT COUNT(*) AS n FROM extraction_batches WHERE generation_id = ?').bind(abandoned.generationId).first()).toEqual({ n: 0 });
    expect(await count(kept.generationId)).toBe(1);
    expect(await found('published')).toHaveLength(1);
    expect(await found('abandoned')).toHaveLength(0);
  });
});

/** Runs the scheduled upkeep at a given time, as the cron trigger would. */
async function expireAt(now: number) {
  await maintainExtraction(env, now);
}
