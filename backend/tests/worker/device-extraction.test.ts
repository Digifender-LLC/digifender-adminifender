import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { EXTRACTION_LEASE_MS, MAX_PAGES_PER_JOB, batchHash, batchManifestHash } from '../../src/extraction/device';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const encoder = new TextEncoder();
const PDF = encoder.encode('%PDF-1.7\n%synthetic scanned statement');

function base64(data: Uint8Array): string {
  return btoa(String.fromCharCode(...data));
}

const mail = (subject = 'Statement') =>
  [
    'From: billing@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${subject}`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain; charset=utf-8',
    '',
    'Statement attached.',
    '--b1',
    'Content-Type: application/pdf; name="scan.pdf"',
    'Content-Disposition: attachment; filename="scan.pdf"',
    'Content-Transfer-Encoding: base64',
    '',
    base64(PDF),
    '--b1--',
    '',
  ].join('\r\n');

const CHARGING = { charging: true, batteryPercent: 80, thermal: 'none' };
type Page = { page: number; text: string; method: 'pdf_text' | 'ocr' };

let token: string;
let counter = 0;
const key = () => `00000000-0000-4000-8000-${String(++counter).padStart(12, '0')}`;
const claim = (body: Record<string, unknown> = CHARGING, now = T0) => api('POST', '/device-extraction/claim', { token, key: key(), now, body });
const send = (attachmentId: string, body: Record<string, unknown>, now = T0) => api('POST', `/device-extraction/${attachmentId}/batch`, { token, key: key(), now, body });

async function deferredPdf(subject?: string): Promise<{ sourceId: string; attachmentId: string }> {
  const sourceId = await ingest(mail(subject));
  await runPending('extract');
  const source = (await api('GET', `/sources/${sourceId}`, { token })).body.data;
  expect(source.attachments[0]).toMatchObject({ extraction: 'deferred', extractionDetail: 'device_unavailable' });
  return { sourceId, attachmentId: source.attachments[0].id };
}

const batch = (work: Record<string, unknown>, batchOrdinal: number, pages: Page[], extra: Record<string, unknown> = {}) => ({
  leaseToken: work.leaseToken,
  contentHash: work.contentHash,
  policyRevision: work.policyRevision,
  generationId: work.generationId,
  batchOrdinal,
  pages,
  complete: false,
  ...extra,
});

const attachmentState = async (sourceId: string) => {
  const a = (await api('GET', `/sources/${sourceId}`, { token })).body.data.attachments[0];
  return `${a.extraction}${a.extractionDetail ? `:${a.extractionDetail}` : ''}`;
};

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('device PDF and OCR extraction', () => {
  it('hands work only to a charging, cool phone with enough battery', async () => {
    await deferredPdf();
    expect((await claim({ ...CHARGING, charging: false })).body.data).toEqual({ work: null, reason: 'not_ready' });
    expect((await claim({ ...CHARGING, batteryPercent: 29 })).body.data).toEqual({ work: null, reason: 'not_ready' });
    expect((await claim({ ...CHARGING, thermal: 'severe' })).body.data).toEqual({ work: null, reason: 'not_ready' });
    const { work } = (await claim()).body.data;
    expect(work).toMatchObject({ nextPage: 1, nextBatch: 0, maxPages: MAX_PAGES_PER_JOB, ocrAllowed: true, sizeBytes: PDF.byteLength });
    expect((await claim()).body.data).toEqual({ work: null, reason: 'busy' });
  });

  it('stores immutable batches in order and publishes only the complete generation', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    const { work } = (await claim()).body.data;
    expect(await attachmentState(sourceId)).toBe('extracting');
    const content = await api('GET', `/attachments/${attachmentId}/content`, { token, raw: true });
    expect(content.headers.get('x-attachment-sha256')).toBe(work.contentHash);

    const first: Page[] = [{ page: 1, text: 'Account statement for October', method: 'pdf_text' }, { page: 2, text: 'Balance due 4471 kroner', method: 'ocr' }];
    expect((await send(attachmentId, batch(work, 0, first))).body.data).toMatchObject({ state: 'extracting', nextBatch: 1, nextPage: 3, batchHash: await batchHash(first) });
    // An identical replay is accepted; the same number with other pages is refused.
    expect((await send(attachmentId, batch(work, 0, first))).body.data.state).toBe('extracting');
    expect((await send(attachmentId, batch(work, 0, [{ page: 1, text: 'altered', method: 'pdf_text' }]))).body.error?.code).toBe('batch_conflict');
    expect((await send(attachmentId, batch(work, 2, [{ page: 5, text: 'x', method: 'ocr' }]))).body.error?.code).toBe('batch_out_of_order');

    // Written but unpublished pages are not readable or searchable.
    expect((await api('GET', '/search?q=kroner', { token })).body.data.results).toEqual([]);
    const chunksBefore = (await api('GET', `/sources/${sourceId}/chunks`, { token })).body.data.chunks;
    expect(chunksBefore.every((c: { attachmentId: string | null }) => c.attachmentId === null)).toBe(true);

    const last: Page[] = [{ page: 3, text: 'Pay before 1 November', method: 'ocr' }];
    const wrong = await send(attachmentId, batch(work, 1, last, { complete: true, manifestHash: 'f'.repeat(64) }));
    expect(wrong.body.error?.code).toBe('manifest_mismatch');
    const manifestHash = await batchManifestHash([await batchHash(first), await batchHash(last)]);
    expect((await send(attachmentId, batch(work, 1, last, { complete: true, manifestHash }))).body.data).toMatchObject({ state: 'ready', generationId: work.generationId });

    expect(await attachmentState(sourceId)).toBe('ready');
    const hits = (await api('GET', '/search?q=kroner', { token })).body.data.results;
    expect(hits).toEqual([expect.objectContaining({ sourceId, attachmentId, page: 2 })]);
    const chunks = (await api('GET', `/sources/${sourceId}/chunks`, { token })).body.data.chunks.filter((c: { attachmentId: string | null }) => c.attachmentId);
    expect(chunks.map((c: { page: number }) => c.page)).toEqual([1, 2, 3]);
    // The lease is over: a late batch under it is refused.
    expect((await send(attachmentId, batch(work, 2, [{ page: 4, text: 'late', method: 'ocr' }]))).body.error?.code).toBe('lease_lost');
  });

  it('resumes from the page checkpoint after a lease expires and refuses the old lease', async () => {
    const { attachmentId } = await deferredPdf();
    const first = (await claim()).body.data.work;
    await send(attachmentId, batch(first, 0, [{ page: 1, text: 'Page one', method: 'pdf_text' }]));
    const later = T0 + EXTRACTION_LEASE_MS + 1;
    const second = (await claim(CHARGING, later)).body.data.work;
    expect(second).toMatchObject({ attachmentId, nextBatch: 1, nextPage: 2 });
    expect(second.leaseToken).not.toBe(first.leaseToken);
    expect((await send(attachmentId, batch(first, 1, [{ page: 2, text: 'Page two', method: 'ocr' }]), later)).body.error?.code).toBe('lease_lost');
    expect((await send(attachmentId, batch(second, 1, [{ page: 2, text: 'Page two', method: 'ocr' }]), later)).body.data.nextPage).toBe(3);
  });

  it('pauses a long document with its pages searchable until the owner resumes it', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    const work = (await claim()).body.data.work;
    const pages: Page[] = [{ page: 1, text: 'First part mentions invoice 9001', method: 'pdf_text' }];
    const manifestHash = await batchManifestHash([await batchHash(pages)]);
    expect((await send(attachmentId, batch(work, 0, pages, { paused: true }))).body.data).toMatchObject({ state: 'deferred', detail: 'pages_remaining' });
    expect(await attachmentState(sourceId)).toBe('deferred:pages_remaining');
    expect((await api('GET', '/search?q=9001', { token })).body.data.results).toHaveLength(1);
    expect((await claim()).body.data).toEqual({ work: null, reason: 'no_work' });

    expect((await api('POST', `/attachments/${attachmentId}/resume-extraction`, { token, key: key(), body: {} })).body.data.detail).toBe('resume_requested');
    const resumed = (await claim()).body.data.work;
    expect(resumed).toMatchObject({ attachmentId, nextBatch: 1, nextPage: 2, generationId: work.generationId, batchHashes: [await batchHash(pages)] });
    const rest: Page[] = [{ page: 2, text: 'Second part', method: 'ocr' }];
    const full = await batchManifestHash([await batchHash(pages), await batchHash(rest)]);
    expect(full).not.toBe(manifestHash);
    expect((await send(attachmentId, batch(resumed, 1, rest, { complete: true, manifestHash: full }))).body.data.state).toBe('ready');
  });

  it('allows PDF text but not OCR for no-AI mail', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    await env.CONTROL_DB.prepare("UPDATE source_registry SET privacy = 'no_ai' WHERE id = ?").bind(sourceId).run();
    const work = (await claim()).body.data.work;
    expect(work.ocrAllowed).toBe(false);
    expect((await send(attachmentId, batch(work, 0, [{ page: 1, text: 'scanned', method: 'ocr' }]))).body.error?.code).toBe('policy_blocked');
    expect((await send(attachmentId, batch(work, 0, [{ page: 1, text: 'embedded text', method: 'pdf_text' }]))).body.data.state).toBe('extracting');
  });

  it('stops accepting pages once the email is quarantined or deleted', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    const work = (await claim()).body.data.work;
    await env.CONTROL_DB.prepare("UPDATE source_registry SET admission = 'quarantine' WHERE id = ?").bind(sourceId).run();
    expect((await send(attachmentId, batch(work, 0, [{ page: 1, text: 'secret', method: 'ocr' }]))).body.error?.code).toBe('deleted');
  });

  it('completes with an empty final batch built from acknowledged hashes', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    const work = (await claim()).body.data.work;
    const acked = (await send(attachmentId, batch(work, 0, [{ page: 1, text: 'Only page', method: 'pdf_text' }]))).body.data.batchHash;
    const empty = await batchHash([]);
    const done = await send(attachmentId, batch(work, 1, [], { complete: true, manifestHash: await batchManifestHash([acked, empty]) }));
    expect(done.body.data).toMatchObject({ state: 'ready', batchHash: empty });
    expect(await attachmentState(sourceId)).toBe('ready');
  });

  it('records files the phone cannot read and keeps the original', async () => {
    const { sourceId, attachmentId } = await deferredPdf();
    const work = (await claim()).body.data.work;
    const failed = await api('POST', `/device-extraction/${attachmentId}/fail`, { token, key: key(), body: { leaseToken: work.leaseToken, reason: 'encrypted' } });
    expect(failed.body.data).toMatchObject({ state: 'unsupported', detail: 'encrypted' });
    expect(await attachmentState(sourceId)).toBe('unsupported:encrypted');
    expect((await api('GET', `/attachments/${attachmentId}/content`, { token, raw: true })).status).toBe(200);
  });
});
