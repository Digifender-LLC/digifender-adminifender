import { describe, expect, it } from 'vitest';
import { locateQuote, resolveAnswerQuotes, resolveTriageQuotes } from '../../src/inference/quotes';

const TEXT = 'Invoice 4471 from Example Hosting.\nAmount due:  $42.00, payable by October 13.';

describe('quote citations', () => {
  it('finds exact quotes, then ignores case, spacing and wrapping quote marks', () => {
    expect(locateQuote(TEXT, 'Example Hosting')).toEqual({ start: 18, end: 33 });
    const loose = locateQuote(TEXT, '"amount due: $42.00"')!;
    expect(TEXT.slice(loose.start, loose.end)).toBe('Amount due:  $42.00');
    const wrapped = locateQuote(TEXT, 'hosting. amount')!;
    expect(TEXT.slice(wrapped.start, wrapped.end)).toBe('Hosting.\nAmount');
  });

  it('refuses quotes that are absent or too short to mean anything', () => {
    expect(locateQuote(TEXT, 'overdue since March')).toBeNull();
    expect(locateQuote(TEXT, '"."')).toBeNull();
    expect(locateQuote(TEXT, 'Io')).toBeNull();
  });

  it('rewrites only quote citations and reports each failure by path', () => {
    const texts = new Map([['c1', TEXT]]);
    const triage = resolveTriageQuotes({ summary: 's', evidence: [{ chunkId: 'c1', quote: 'October 13' }, { chunkId: 'c1', start: 0, end: 7 }] }, texts);
    expect(triage).toEqual({ value: { summary: 's', evidence: [{ chunkId: 'c1', start: TEXT.indexOf('October 13'), end: TEXT.indexOf('October 13') + 10 }, { chunkId: 'c1', start: 0, end: 7 }] }, errors: [] });
    const answer = resolveAnswerQuotes({ claims: [{ text: 't', citations: [{ chunkId: 'c2', quote: 'October 13' }, { chunkId: 'c1', quote: 'nowhere' }, { chunkId: 'c1', quote: 7 }] }] }, texts);
    expect(answer.errors).toEqual(['$.claims[0].citations[0].chunkId:not_in_context', '$.claims[0].citations[1].quote:not_in_chunk', '$.claims[0].citations[2].quote:invalid']);
  });
});
