import { describe, expect, it } from 'vitest';
import { MAX_CHUNK_CHARS, bodyText, chunkText, normalizeEmail } from '../../src/archive/normalize';

const eml = import.meta.glob<string>('../../../contracts/fixtures/mime/*.eml', { query: '?raw', import: 'default', eager: true });
const golden = import.meta.glob<string>('../../../contracts/fixtures/mime/*.txt', { query: '?raw', import: 'default', eager: true });
const meta = import.meta.glob<{ attachments: unknown[] }>('../../../contracts/fixtures/mime/*.json', { import: 'default', eager: true });

const fixture = (name: string): string => {
  const value = eml[`../../../contracts/fixtures/mime/${name}.eml`];
  if (value === undefined) throw new Error(`missing fixture ${name}`);
  return value;
};

describe('MIME golden text fixtures', () => {
  const names = Object.keys(golden).map((path) => path.replace(/^.*\/(.*)\.txt$/, '$1'));

  it('has a golden text for every valid fixture', () => {
    const valid = Object.keys(eml).map((path) => path.replace(/^.*\/(.*)\.eml$/, '$1')).filter((name) => !name.startsWith('invalid-'));
    expect(names.sort()).toEqual(valid.sort());
  });

  for (const name of names) {
    it(`normalizes ${name}`, async () => {
      const result = await normalizeEmail(new TextEncoder().encode(fixture(name)));
      expect(result.text).toBe(golden[`../../../contracts/fixtures/mime/${name}.txt`]);
      expect(result.chunks.map((c) => c.text).join('')).toBe(result.text);
    });
  }

  it('decodes encoded subjects and senders', async () => {
    const result = await normalizeEmail(new TextEncoder().encode(fixture('unicode-qp')));
    expect(result.subject).toBe('Réunion — décision');
    expect(result.envelope.from).toEqual(['elodie@example.fr']);
    expect(result.envelope.messageId).toBe('<u1@example.fr>');
  });

  it('retains inline and attached files with exact hashes', async () => {
    const result = await normalizeEmail(new TextEncoder().encode(fixture('attachments-no-message-id')));
    const expected = meta['../../../contracts/fixtures/mime/attachments-no-message-id.json']?.attachments;
    expect(result.attachments.map(({ bytes: _bytes, ...rest }) => rest)).toEqual(expected);
    expect(result.envelope.messageId).toBeNull();
  });

  it('rejects MIME nested beyond the bound so the raw source stays for repair', async () => {
    await expect(normalizeEmail(new TextEncoder().encode(fixture('invalid-nesting')))).rejects.toThrow();
  });

  it('never executes or keeps script, style or javascript links', async () => {
    const result = await normalizeEmail(new TextEncoder().encode(fixture('html-only')));
    expect(result.text).not.toMatch(/alert|color:red|javascript:|tracker/);
  });
});

describe('body selection and chunking', () => {
  it('prefers one copy when the alternatives agree', () => {
    expect(bodyText({ text: 'Same words here.', html: '<p>Same  words here.</p>' })).toBe('Same words here.');
  });

  it('splits long text deterministically within the chunk bound', () => {
    const paragraph = 'word '.repeat(300).trim();
    const text = Array.from({ length: 30 }, (_, i) => `${i}: ${paragraph}`).join('\n\n');
    const chunks = chunkText(text);
    expect(chunks.length).toBeGreaterThan(1);
    expect(chunks.every((c) => c.text.length <= MAX_CHUNK_CHARS)).toBe(true);
    expect(chunks.map((c) => c.text).join('')).toBe(text);
    expect(chunks.map((c) => c.startOffset)).toEqual(chunkText(text).map((c) => c.startOffset));
    for (const chunk of chunks) expect(text.slice(chunk.startOffset, chunk.startOffset + chunk.text.length)).toBe(chunk.text);
  });

  it('cuts unbroken text without splitting surrogate pairs', () => {
    const text = '📧'.repeat(5000);
    const chunks = chunkText(text);
    expect(chunks.map((c) => c.text).join('')).toBe(text);
    for (const chunk of chunks) expect(chunk.text.length % 2).toBe(0);
  });
});
