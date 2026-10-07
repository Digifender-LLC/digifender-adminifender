import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { canonicalJson } from '../../src/http/idempotency';
import { sha256Hex } from '../../src/ids';
import { anyDeviceReady } from '../../src/inference/job';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, ingest, runPending } from './support';

const readiness = { charging: true, batteryPercent: 70, thermal: 'none', pdfText: true, latinOcr: false };
const pdf = '%PDF-1.7\n% Synthetic transport fixture, never executed as a real PDF.\n';
const mail = ['From: statements@vendor.example', 'To: inbox@adminifender.example', 'Subject: Device extraction fixture', 'MIME-Version: 1.0', 'Content-Type: multipart/mixed; boundary="device-test"', '', '--device-test', 'Content-Type: text/plain', '', 'A synthetic attachment.', '--device-test', 'Content-Type: application/pdf; name="invoice.pdf"', 'Content-Disposition: attachment; filename="invoice.pdf"', '', pdf, '--device-test--', ''].join('\r\n');
const part = (text: string, page = 0) => ({ page, part: 0, startOffset: 0, text, method: 'pdf_text', lastPart: true });

describe('device extraction transport', () => {
  let token: string;
  let deviceId: string;
  let sourceId: string;
  const post = (path: string, body: unknown, now = T0, extraEnv?: Env) => api('POST', `/device-work/extraction${path}`, { token, body, key: crypto.randomUUID(), now, ...(extraEnv ? { env: extraEnv } : {}) });
  beforeEach(async () => {
    const owner = await enrollOwner();
    token = owner.token;
    deviceId = owner.deviceId;
    sourceId = await ingest(mail);
    await runPending('extract');
  });

  const claim = async (now = T0) => {
    const response = await post('/claim', readiness, now);
    expect(response.status).toBe(200);
    expect(response.body.data.work).not.toBeNull();
    return response.body.data.work;
  };
  const batch = (work: any, ordinal: number, parts: ReturnType<typeof part>[]) => ({
    leaseToken: work.leaseToken, generationId: work.generationId, contentHash: work.contentHash,
    sourceRevision: work.sourceRevision, policyRevision: work.policyRevision, extractorVersion: work.extractorVersion,
    batchOrdinal: ordinal, parts,
  });
  const finish = async (work: any, hashes: string[], now = T0, extraEnv?: Env) => post(`/${work.jobId}/finish`, {
    leaseToken: work.leaseToken, generationId: work.generationId, contentHash: work.contentHash,
    sourceRevision: work.sourceRevision, policyRevision: work.policyRevision, extractorVersion: work.extractorVersion,
    manifestHash: await sha256Hex(canonicalJson(hashes.map((hash, ordinal) => ({ ordinal, hash })))), complete: true,
  }, now, extraEnv);

  it('publishes only a completed manifest and keeps original bytes', async () => {
    const work = await claim();
    const original = await api('GET', `/attachments/${work.attachmentId}/content`, { token });
    const submitted = await post(`/${work.jobId}/batches`, batch(work, 0, [part('Synthetic searchable invoice') ]));
    expect(submitted.status).toBe(200);
    expect(submitted.body.data).toMatchObject({ nextPage: 1, nextPart: 0, nextBatch: 1 });
    expect((await api('GET', '/search?q=searchable', { token })).body.data.results).toEqual([]);
    const result = await finish(work, [submitted.body.data.batchHash]);
    expect(result.status).toBe(200);
    expect(result.body.data.state).toBe('ready');
    expect((await api('GET', '/search?q=searchable', { token })).body.data.results).toHaveLength(1);
    expect((await api('GET', `/attachments/${work.attachmentId}/content`, { token })).body.data).toEqual(original.body.data);
  });

  it('deduplicates acknowledged batches and refuses altered batch identity and manifest', async () => {
    const work = await claim();
    const body = batch(work, 0, [part('First text')]);
    const first = await post(`/${work.jobId}/batches`, body);
    expect((await post(`/${work.jobId}/batches`, body)).body.data).toEqual(first.body.data);
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [part('Altered text')]))).body.error?.code).toBe('batch_conflict');
    expect((await finish(work, ['0'.repeat(64)])).body.error?.code).toBe('manifest_mismatch');
    expect((await api('GET', '/search?q=First', { token })).body.data.results).toEqual([]);
  });

  it('reclaims expired work with a new fence and preserves only the acknowledged prefix', async () => {
    const work = await claim();
    const first = await post(`/${work.jobId}/batches`, batch(work, 0, [part('Retained prefix')]));
    const later = T0 + 300_001;
    const resumed = await claim(later);
    expect(resumed.leaseToken).not.toBe(work.leaseToken);
    expect(resumed.generationId).not.toBe(work.generationId);
    expect(resumed.checkpoint).toMatchObject({ nextPage: 1, nextPart: 0, nextBatch: 1 });
    expect((await post(`/${work.jobId}/batches`, batch(work, 1, [part('Late poison', 1)]), later)).body.error?.code).toBe('stale_job');
    const second = await post(`/${resumed.jobId}/batches`, batch(resumed, 1, [part('New suffix', 1)]), later);
    expect((await finish(resumed, [first.body.data.batchHash, second.body.data.batchHash], later)).status).toBe(200);
    expect((await api('GET', '/search?q=poison', { token, now: later })).body.data.results).toEqual([]);
    expect((await api('GET', '/search?q=prefix', { token, now: later })).body.data.results).toHaveLength(1);
  });

  it('applies current source policy to every result and never treats no-AI as OCR permission', async () => {
    const work = await claim();
    await env.CONTROL_DB.prepare("UPDATE source_registry SET privacy = 'no_ai', revision = revision + 1 WHERE id = ?").bind(sourceId).run();
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [part('Old policy')]))).body.error?.code).toBe('stale_job');
    const resumed = await claim(T0 + 300_001);
    const ocr = { ...part('OCR forbidden'), method: 'ocr' };
    expect((await post(`/${resumed.jobId}/batches`, batch(resumed, 0, [ocr]), T0 + 300_001)).body.error?.code).toBe('policy_blocked');
    expect((await post(`/${resumed.jobId}/batches`, batch(resumed, 0, [part('Allowed native text')]), T0 + 300_001)).status).toBe(200);
  });

  it('refuses oversized/noncontiguous parts and stops when the device is revoked or source deleted', async () => {
    const work = await claim();
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [part('Skipped page', 1)]))).body.error?.code).toBe('checkpoint_mismatch');
    const tooMuch = Array.from({ length: 30 }, (_, index) => ({ ...part('é'.repeat(5000)), part: index, startOffset: index * 5000, lastPart: index === 29 }));
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, tooMuch))).status).toBeGreaterThanOrEqual(400);
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleting' WHERE id = ?").bind(sourceId).run();
    expect((await post(`/${work.jobId}/heartbeat`, { leaseToken: work.leaseToken })).body.error?.code).toBe('deleted');
    await env.CONTROL_DB.prepare('UPDATE devices SET revoked_at = ? WHERE id = ?').bind(T0, deviceId).run();
    expect((await post(`/${work.jobId}/heartbeat`, { leaseToken: work.leaseToken })).status).toBe(401);
  });

  it('does not claim without the real extraction capability, charging or under quarantine', async () => {
    expect((await post('/claim', { ...readiness, pdfText: false })).body.data).toEqual({ work: null, reason: 'not_ready' });
    expect((await post('/claim', { ...readiness, charging: false })).body.data).toEqual({ work: null, reason: 'not_ready' });
    await env.CONTROL_DB.prepare("UPDATE source_registry SET admission = 'quarantine' WHERE id = ?").bind(sourceId).run();
    expect((await post('/claim', readiness)).body.data).toEqual({ work: null, reason: 'no_work' });
  });

  const inferenceReadiness = { foreground: false, charging: true, batteryPercent: 70, thermal: 'none', modelId: 'synthetic-model', modelManifestHash: 'a'.repeat(64) };
  const inferenceClaim = (now = T0) => api('POST', '/device-work/claim', { token, body: inferenceReadiness, key: crypto.randomUUID(), now });
  const prepareInference = async () => { await runPending('triage'); await runPending('infer'); };

  it('shares a single device slot with inference and does not route onto a busy device', async () => {
    await prepareInference();
    const extraction = await claim();
    expect((await inferenceClaim()).body.data).toEqual({ work: null, reason: 'busy' });
    expect(await anyDeviceReady(env.CONTROL_DB, T0)).toBe(false);
    const later = T0 + 300_001;
    expect((await inferenceClaim(later)).body.data.work).not.toBeNull();
    expect((await post('/claim', readiness, later)).body.data).toEqual({ work: null, reason: 'busy' });
    expect((await post(`/${extraction.jobId}/heartbeat`, { leaseToken: extraction.leaseToken }, later)).body.error?.code).toBe('stale_job');
  });

  it('atomically grants only one of concurrent extraction/inference claims', async () => {
    await prepareInference();
    const claims = await Promise.all([post('/claim', readiness), inferenceClaim()]);
    expect(claims.every((response) => response.status === 200)).toBe(true);
    expect(claims.filter((response) => response.body.data.work !== null)).toHaveLength(1);
    const leases = await env.CONTROL_DB.prepare("SELECT (SELECT count(*) FROM inference_attempts WHERE state = 'leased') + (SELECT count(*) FROM device_extraction_jobs WHERE state = 'leased') AS total").first<{ total: number }>();
    expect(leases?.total).toBe(1);
  });

  it('preserves page-part offsets and reports unavailable OCR without empty success', async () => {
    const work = await claim();
    const first = { ...part('First half'), lastPart: false };
    const acknowledged = await post(`/${work.jobId}/batches`, batch(work, 0, [first]));
    expect(acknowledged.body.data).toMatchObject({ nextPage: 0, nextPart: 1, nextOffset: 10 });
    expect((await finish(work, [acknowledged.body.data.batchHash])).body.error?.code).toBe('checkpoint_mismatch');
    const second = { ...part(' second half'), part: 1, startOffset: 10 };
    expect((await post(`/${work.jobId}/batches`, batch(work, 1, [second]))).status).toBe(200);
    const { batchOrdinal: _ordinal, parts: _parts, ...fence } = batch(work, 0, []);
    const deferred = await post(`/${work.jobId}/defer`, { ...fence, reason: 'ocr_unavailable' });
    expect(deferred.body.data).toMatchObject({ state: 'deferred', detail: 'ocr_unavailable', nextPage: 1 });
    expect((await post('/claim', readiness)).body.data.work).toBeNull();
    expect((await post(`/${work.jobId}/resume`, { expectedRevision: deferred.body.data.revision })).status).toBe(200);
    expect((await claim()).checkpoint).toMatchObject({ nextPage: 1, nextPart: 0, nextBatch: 2 });
    expect((await api('GET', '/search?q=half', { token })).body.data.results).toEqual([]);
  });

  it('deletion cleanup removes staged source text and cascades its job and batch ledgers', async () => {
    const work = await claim();
    await post(`/${work.jobId}/batches`, batch(work, 0, [part('Delete these staged words')]));
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `text/${sourceId}/device/` })).objects.length).toBe(1);
    const review = await api('POST', '/reviews', { token, key: crypto.randomUUID(), body: { effect: 'archive.delete', params: { sourceIds: [sourceId] } } });
    expect(review.status).toBe(200);
    expect((await api('POST', `/reviews/${review.body.data.reviewId}/confirm`, { token, key: crypto.randomUUID(), body: { paramsDigest: review.body.data.paramsDigest } })).status).toBe(200);
    expect(await runPending('cleanup')).toEqual(['complete']);
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `text/${sourceId}/device/` })).objects).toEqual([]);
    expect(await env.CONTROL_DB.prepare('SELECT count(*) AS n FROM device_extraction_jobs').first()).toEqual({ n: 0 });
    expect(await env.CONTROL_DB.prepare('SELECT count(*) AS n FROM device_extraction_batches').first()).toEqual({ n: 0 });
  });

  const beforePut = (callback: () => Promise<void>): Env => ({ ...env, ARCHIVE_BUCKET: new Proxy(env.ARCHIVE_BUCKET, {
    get(target, property) {
      if (property === 'put') return async (...args: Parameters<R2Bucket['put']>) => { await callback(); return target.put(...args); };
      const value = Reflect.get(target, property);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  }) });

  it('cleans an object whose write lands after source deletion cleanup already finished', async () => {
    const work = await claim();
    const racing = beforePut(async () => {
      const review = await api('POST', '/reviews', { token, key: crypto.randomUUID(), body: { effect: 'archive.delete', params: { sourceIds: [sourceId] } } });
      expect((await api('POST', `/reviews/${review.body.data.reviewId}/confirm`, { token, key: crypto.randomUUID(), body: { paramsDigest: review.body.data.paramsDigest } })).status).toBe(200);
      expect(await runPending('cleanup')).toEqual(['complete']);
    });
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [part('Late private bytes')]), T0, racing)).body.error?.code).toBe('deleted');
    expect((await env.ARCHIVE_BUCKET.list({ prefix: `text/${sourceId}/device/` })).objects).toEqual([]);
  });

  it('does not acknowledge source text after revocation while a batch upload is in flight', async () => {
    const work = await claim();
    const racing = beforePut(async () => { await env.CONTROL_DB.prepare('UPDATE devices SET revoked_at = ? WHERE id = ?').bind(T0, deviceId).run(); });
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [part('Revoked device output')]), T0, racing)).body.error?.code).toBe('stale_job');
    expect(await env.CONTROL_DB.prepare('SELECT count(*) AS n FROM device_extraction_batches').first()).toEqual({ n: 0 });
    expect(await env.CONTROL_DB.prepare('SELECT active_generation FROM attachment_processing WHERE attachment_id = ?').bind(work.attachmentId).first()).toEqual({ active_generation: null });
  });

  it('keeps a late final archive write hidden after another lease reclaims the job', async () => {
    const work = await claim();
    const ack = await post(`/${work.jobId}/batches`, batch(work, 0, [part('Fenced final text')]));
    let resumed: any = null;
    const racing: Env = { ...env, ARCHIVE_0001: new Proxy(env.ARCHIVE_0001, {
      get(target, property) {
        if (property === 'batch') return async (statements: D1PreparedStatement[]) => {
          const result = await target.batch(statements);
          if (!resumed) resumed = await claim(T0 + 300_001);
          return result;
        };
        const value = Reflect.get(target, property);
        return typeof value === 'function' ? value.bind(target) : value;
      },
    }) };
    expect((await finish(work, [ack.body.data.batchHash], T0, racing)).body.error?.code).toBe('stale_job');
    expect((await api('GET', '/search?q=Fenced', { token })).body.data.results).toEqual([]);
    expect((await finish(resumed, [ack.body.data.batchHash], T0 + 300_001)).status).toBe(200);
    expect((await api('GET', '/search?q=Fenced', { token })).body.data.results).toHaveLength(1);
  });

  it('never carries previously acknowledged OCR across a source privacy revision', async () => {
    const work = (await post('/claim', { ...readiness, latinOcr: true })).body.data.work;
    expect((await post(`/${work.jobId}/batches`, batch(work, 0, [{ ...part('Old OCR phrase'), method: 'ocr' }]))).status).toBe(200);
    await env.CONTROL_DB.prepare("UPDATE source_registry SET privacy = 'no_ai', revision = revision + 1 WHERE id = ?").bind(sourceId).run();
    const later = T0 + 300_001;
    const resumed = await claim(later);
    expect(resumed.checkpoint).toEqual({ nextPage: 0, nextPart: 0, nextOffset: 0, nextBatch: 0 });
    expect(resumed.acceptedBatches).toEqual([]);
    const ack = await post(`/${resumed.jobId}/batches`, batch(resumed, 0, [part('Permitted deterministic text')]), later);
    expect((await finish(resumed, [ack.body.data.batchHash], later)).status).toBe(200);
    expect((await api('GET', '/search?q=phrase', { token, now: later })).body.data.results).toEqual([]);
  });

  it('requires an explicit revisioned resume after a hundred-page pass', async () => {
    const work = await claim();
    const hashes: string[] = [];
    for (let ordinal = 0; ordinal < 10; ordinal += 1) {
      const ack = await post(`/${work.jobId}/batches`, batch(work, ordinal, Array.from({ length: 10 }, (_, index) => part(`Page ${ordinal * 10 + index}`, ordinal * 10 + index))));
      expect(ack.status).toBe(200);
      hashes.push(ack.body.data.batchHash);
    }
    expect((await post(`/${work.jobId}/batches`, batch(work, 10, [part('Next page', 100)]))).body.error?.code).toBe('page_limit');
    const { batchOrdinal: _ordinal, parts: _parts, ...fence } = batch(work, 0, []);
    const paused = await post(`/${work.jobId}/finish`, { ...fence, complete: false, manifestHash: await sha256Hex(canonicalJson(hashes.map((hash, ordinal) => ({ ordinal, hash })))) });
    expect(paused.body.data).toMatchObject({ state: 'deferred', detail: 'page_limit', nextPage: 100 });
    expect((await post('/claim', readiness)).body.data.work).toBeNull();
    expect((await post(`/${work.jobId}/resume`, { expectedRevision: paused.body.data.revision - 1 })).body.error?.code).toBe('stale_job');
    expect((await post(`/${work.jobId}/resume`, { expectedRevision: paused.body.data.revision })).status).toBe(200);
    const resumed = await claim();
    expect(resumed.maxPageExclusive).toBe(200);
    expect(resumed.checkpoint.nextPage).toBe(100);
    const ack = await post(`/${resumed.jobId}/batches`, batch(resumed, 10, [part('Explicitly resumed page', 100)]));
    expect((await finish(resumed, [...hashes, ack.body.data.batchHash])).status).toBe(200);
    expect((await api('GET', '/search?q=resumed', { token })).body.data.results).toHaveLength(1);
  });
});
