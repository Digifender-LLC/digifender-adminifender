import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { docxText, wordXmlText } from '../../src/extraction/docx';
import { generationFor } from '../../src/extraction/job';
import { decodeText, detectKind } from '../../src/extraction/text';
import { ExtractionError } from '../../src/extraction/zip';
import { JOB_HANDLERS } from '../../src/jobs/handlers';
import { claimJob, runJob } from '../../src/jobs/runner';
import { T0, api, enrollOwner, ingest, runPending } from './support';
import { buildZip, docx, para } from './zip-builder';

const encoder = new TextEncoder();
const bytes = (text: string) => encoder.encode(text);
const detail = async (fn: () => Promise<unknown>) => {
  try {
    await fn();
  } catch (error) {
    if (error instanceof ExtractionError) return `${error.detail}:${error.reason}`;
    throw error;
  }
  return 'none';
};

function base64(data: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < data.length; i += 0x8000) binary += String.fromCharCode(...data.subarray(i, i + 0x8000));
  return btoa(binary).replace(/.{76}/g, '$&\r\n');
}

interface Part {
  name: string;
  type: string;
  data: Uint8Array;
}

const mail = (parts: Part[], o: { subject?: string; from?: string } = {}) =>
  [
    `From: ${o.from ?? 'reports@vendor.example'}`,
    'To: inbox@adminifender.example',
    `Subject: ${o.subject ?? 'Monthly files'}`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain; charset=utf-8',
    '',
    'Files attached.',
    ...parts.flatMap((p) => ['--b1', `Content-Type: ${p.type}; name="${p.name}"`, `Content-Disposition: attachment; filename="${p.name}"`, 'Content-Transfer-Encoding: base64', '', base64(p.data)]),
    '--b1--',
    '',
  ].join('\r\n');

describe('type detection and decoding', () => {
  it('trusts content signatures before labels', () => {
    expect(detectKind('a.txt', 'text/plain', bytes('%PDF-1.7'))).toBe('pdf');
    expect(detectKind('a.csv', 'text/csv', new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0, 0]))).toBe('image');
    expect(detectKind('a.zip', 'application/zip', new Uint8Array([0x50, 0x4b, 3, 4]))).toBe('unsupported');
    expect(detectKind('a.docx', null, new Uint8Array([0x50, 0x4b, 3, 4]))).toBe('docx');
    expect(detectKind('data.csv', 'application/octet-stream', bytes('a,b\n1,2'))).toBe('text');
    expect(detectKind('notes.md', null, bytes('# Title'))).toBe('text');
    expect(detectKind('page.htm', null, bytes('<p>x</p>'))).toBe('html');
    expect(detectKind('a.txt', 'text/plain', new Uint8Array([0x41, 0, 0x42]))).toBe('unsupported');
    expect(detectKind('song.mp3', 'audio/mpeg', bytes('ID3'))).toBe('unsupported');
  });

  it('decodes UTF-8, UTF-16 by BOM and falls back to windows-1252', () => {
    expect(decodeText(bytes('﻿café'))).toBe('café');
    expect(decodeText(new Uint8Array([0xff, 0xfe, 0x68, 0, 0x69, 0]))).toBe('hi');
    expect(decodeText(new Uint8Array([0x63, 0x61, 0x66, 0xe9, 0x80]))).toBe('café€');
  });
});

describe('DOCX', () => {
  it('extracts paragraphs, breaks, tabs, tables, entities and notes', async () => {
    const table = '<w:tbl><w:tr><w:tc>' + para('Plan') + '</w:tc><w:tc>' + para('Cost') + '</w:tc></w:tr><w:tr><w:tc>' + para('Pro') + '</w:tc><w:tc>' + para('&#8364;12 &amp; tax') + '</w:tc></w:tr></w:tbl>';
    const body = para('Renewal ', 'notice') + '<w:p><w:r><w:t>Line one</w:t><w:br/><w:t>Line two</w:t><w:tab/><w:t>tabbed</w:t></w:r></w:p>' + table;
    const notes = { name: 'word/footnotes.xml', data: bytes(`<w:footnotes>${para('A footnote')}</w:footnotes>`) };
    const text = await docxText(await docx(body, [notes]));
    expect(text).toContain('Renewal notice\nLine one\nLine two\ttabbed\n');
    expect(text).toContain('Plan\tCost\nPro\t€12 & tax\n');
    expect(text).toContain('A footnote');
  });

  it('refuses document type declarations instead of expanding entities', () => {
    expect(() => wordXmlText('<!DOCTYPE x [<!ENTITY a "aaaa">]><w:t>&a;</w:t>')).toThrow(ExtractionError);
  });

  it('enforces ratio, declared size, entry count, encryption and structure limits', async () => {
    const zeros = new Uint8Array(5 * 1024 * 1024);
    expect(await detail(async () => docxText(await docx(para('x'), [{ name: 'word/media/big.bin', data: zeros }])))).toBe('limit:ratio');
    const lying = await buildZip([
      { name: '[Content_Types].xml', data: bytes('<Types/>') },
      { name: 'word/document.xml', data: bytes(`<w:document>${para('y'.repeat(100_000))}</w:document>`), declaredSize: 2000 },
    ]);
    expect(await detail(() => docxText(lying))).toBe('limit:declared_size');
    const many = await buildZip(Array.from({ length: 1001 }, (_, i) => ({ name: `f${i}`, data: bytes('x'), method: 0 as const })));
    expect(await detail(() => docxText(many))).toBe('limit:entries');
    const encrypted = await buildZip([{ name: 'word/document.xml', data: bytes('x'), flags: 1 }]);
    expect(await detail(() => docxText(encrypted))).toBe('encrypted:zip_encrypted');
    expect(await detail(async () => docxText(await buildZip([{ name: 'other.xml', data: bytes('x') }])))).toBe('parser_error:not_docx');
    expect(await detail(() => docxText(bytes('PK\u0003\u0004 not really a zip')))).toBe('parser_error:zip_directory');
  });
});

describe('extraction jobs', () => {
  let token: string;
  beforeEach(async () => {
    token = (await enrollOwner()).token;
  });

  const states = async (sourceId: string) =>
    Object.fromEntries(
      (await api('GET', `/sources/${sourceId}`, { token })).body.data.attachments.map((a: { name: string; extraction: string; extractionDetail: string | null }) => [a.name, `${a.extraction}${a.extractionDetail ? `:${a.extractionDetail}` : ''}`]),
    );

  it('indexes supported files, defers device work and leaves others downloadable', async () => {
    const report = await docx(para('Quarterly uptime was 99.95 percent'));
    const parts: Part[] = [
      { name: 'usage.csv', type: 'text/csv', data: bytes('service,cost\nstorage,42\n') },
      { name: 'report.docx', type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', data: report },
      { name: 'scan.pdf', type: 'application/pdf', data: bytes('%PDF-1.7\n%binary') },
      { name: 'voicemail.mp3', type: 'audio/mpeg', data: bytes('ID3\u0004\u0000binary') },
      { name: 'broken.docx', type: 'application/octet-stream', data: bytes('PK\u0003\u0004garbage') },
    ];
    const sourceId = await ingest(mail(parts));
    expect(await states(sourceId)).toEqual({ 'usage.csv': 'queued', 'report.docx': 'queued', 'scan.pdf': 'queued', 'voicemail.mp3': 'queued', 'broken.docx': 'queued' });
    expect(await runPending('extract')).toEqual(Array(5).fill('complete'));
    expect(await states(sourceId)).toEqual({
      'usage.csv': 'ready',
      'report.docx': 'ready',
      'scan.pdf': 'deferred:device_unavailable',
      'voicemail.mp3': 'unsupported:unsupported_type',
      'broken.docx': 'failed:parser_error',
    });

    const found = await api('GET', '/search?q=uptime', { token });
    expect(found.body.data.results).toHaveLength(1);
    const attachments = (await api('GET', `/sources/${sourceId}`, { token })).body.data.attachments as { id: string; name: string; sha256: string }[];
    const docxId = attachments.find((a) => a.name === 'report.docx')!.id;
    expect(found.body.data.results[0]).toMatchObject({ sourceId, attachmentId: docxId });
    const chunks = (await api('GET', `/sources/${sourceId}/chunks`, { token })).body.data.chunks as { attachmentId: string | null; text: string }[];
    expect(chunks.filter((c) => c.attachmentId !== null).map((c) => c.text).sort()).toEqual(['Quarterly uptime was 99.95 percent', 'service,cost\nstorage,42']);

    // Originals are untouched.
    const download = await api('GET', `/attachments/${docxId}/content`, { token });
    expect(download.body.data).toEqual(report);
    const changes = await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM changes WHERE entity_type = 'attachment'").first();
    expect(changes).toEqual({ n: 5 });
  });

  it('never makes an unpublished generation searchable', async () => {
    const sourceId = await ingest(mail([{ name: 'a.txt', type: 'text/plain', data: bytes('visible words') }]));
    const attachmentId = (await env.CONTROL_DB.prepare('SELECT attachment_id FROM attachment_processing WHERE source_id = ?').bind(sourceId).first<{ attachment_id: string }>())!.attachment_id;
    // A late writer's chunks exist in the archive but no control pointer publishes them.
    await env.ARCHIVE_0001.batch([
      env.ARCHIVE_0001.prepare("INSERT INTO chunks(id, source_id, attachment_id, generation_id, ordinal, text, text_hash, method) VALUES ('late', ?, ?, 'att:late', 0, 'hidden phrase', 'h', 'text')").bind(sourceId, attachmentId),
      env.ARCHIVE_0001.prepare("INSERT INTO source_fts(source_id, chunk_id, generation_id, subject, body, filename) VALUES (?, 'late', 'att:late', '', 'hidden phrase', '')").bind(sourceId),
    ]);
    await runPending('extract');
    expect((await api('GET', '/search?q=hidden', { token })).body.data.results).toEqual([]);
    expect((await api('GET', '/search?q=visible', { token })).body.data.results).toHaveLength(1);
    expect(((await api('GET', `/sources/${sourceId}/chunks`, { token })).body.data.chunks as { text: string }[]).map((c) => c.text)).not.toContain('hidden phrase');
  });

  it('does not extract quarantined mail, and release queues it again', async () => {
    const spam = 'From: a@x.example, b@y.example\r\nTo: inbox@adminifender.example\r\nSubject: Prize\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary="b1"\r\n\r\n--b1\r\nContent-Type: text/html\r\n\r\n<p>Claim your prize now.</p><a href="https://evil.example/">bank.example</a>\r\n--b1\r\nContent-Type: text/plain; name="claim.txt"\r\nContent-Disposition: attachment; filename="claim.txt"\r\n\r\nclaim code\r\n--b1--\r\n';
    const sourceId = await ingest(spam);
    expect(await env.CONTROL_DB.prepare("SELECT COUNT(*) AS n FROM jobs WHERE kind = 'extract'").first()).toEqual({ n: 0 });
    expect(await states(sourceId)).toEqual({ 'claim.txt': 'stored' });
    await api('POST', `/sources/${sourceId}/release`, { token, key: crypto.randomUUID(), body: { expectedRevision: 2 } });
    expect(await runPending('extract')).toEqual(['complete']);
    expect(await states(sourceId)).toEqual({ 'claim.txt': 'ready' });
  });

  it('cancels queued extraction on quarantine and requeues under a new key on release', async () => {
    const sourceId = await ingest(mail([{ name: 'a.txt', type: 'text/plain', data: bytes('quarterly words') }]));
    expect((await api('POST', `/sources/${sourceId}/quarantine`, { token, key: crypto.randomUUID(), body: { expectedRevision: 2 } })).status).toBe(200);
    expect(await states(sourceId)).toEqual({ 'a.txt': 'stored' });
    expect(await runPending('extract')).toEqual([]);
    await api('POST', `/sources/${sourceId}/release`, { token, key: crypto.randomUUID(), body: { expectedRevision: 3 } });
    expect(await runPending('extract')).toEqual(['complete']);
    expect(await states(sourceId)).toEqual({ 'a.txt': 'ready' });
  });

  it('retries without duplicating chunks and ignores superseded jobs', async () => {
    const sourceId = await ingest(mail([{ name: 'a.txt', type: 'text/plain', data: bytes('once only') }]));
    const job = await env.CONTROL_DB.prepare("SELECT id FROM jobs WHERE kind = 'extract'").first<{ id: string }>();
    const claim = await claimJob(env.CONTROL_DB, job!.id, T0);
    await JOB_HANDLERS.extract!(claim!, env, T0); // archive writes land; settle never happens
    expect(await runPending('extract', T0 + 6 * 60_000)).toEqual([]);
    expect((await runJob(env, JOB_HANDLERS, job!.id, T0 + 6 * 60_000)).status).toBe('complete');
    const attachmentId = (await env.CONTROL_DB.prepare('SELECT attachment_id, active_generation FROM attachment_processing WHERE source_id = ?').bind(sourceId).first<{ attachment_id: string; active_generation: string }>())!;
    expect(attachmentId.active_generation).toBe(generationFor(attachmentId.attachment_id));
    expect(await env.ARCHIVE_0001.prepare('SELECT COUNT(*) AS n FROM chunks WHERE attachment_id IS NOT NULL').first()).toEqual({ n: 1 });
    expect(await env.ARCHIVE_0001.prepare("SELECT COUNT(*) AS n FROM source_fts WHERE generation_id LIKE 'att:%'").first()).toEqual({ n: 1 });
  });
});
