import { describe, expect, it } from 'vitest';
import { normalizeDomain, parseAddrSpec, parseSingleMailbox } from '../../src/admission/addresses';
import { type BlockRule, findBlockingRule, normalizeBlockValue } from '../../src/admission/blocking';

describe('address normalization', () => {
  it('lowercases domains and converts them to IDNA ASCII', () => {
    expect(normalizeDomain('Example.COM')).toBe('example.com');
    expect(normalizeDomain('bücher.example')).toBe('xn--bcher-kva.example');
    expect(normalizeDomain('mail.example.org.')).toBe('mail.example.org');
  });

  it('rejects wildcards, IP literals and single labels', () => {
    for (const value of ['*.example.com', '192.0.2.1', 'localhost', 'exa mple.com', '', 'a@b.com']) expect(normalizeDomain(value)).toBeNull();
  });

  it('preserves the local part exactly', () => {
    expect(parseAddrSpec('Billing.Team@Example.COM')).toEqual({ local: 'Billing.Team', domain: 'example.com' });
    expect(parseAddrSpec('@example.com')).toBeNull();
    expect(parseAddrSpec('person@')).toBeNull();
  });

  it('accepts exactly one From mailbox', () => {
    expect(parseSingleMailbox('"Ops, Team" <ops@example.com>')).toEqual({ local: 'ops', domain: 'example.com' });
    expect(parseSingleMailbox('ops@example.com')).toEqual({ local: 'ops', domain: 'example.com' });
    expect(parseSingleMailbox('a@example.com, b@example.com')).toBeNull();
    expect(parseSingleMailbox('Group: a@example.com;')).toBeNull();
    expect(parseSingleMailbox('Name <a@example.com> <b@example.com>')).toBeNull();
    expect(parseSingleMailbox('"unterminated <a@example.com>')).toBeNull();
    expect(parseSingleMailbox(null)).toBeNull();
  });
});

describe('block rules', () => {
  const rule = (over: Partial<BlockRule>): BlockRule => ({ id: 'r', kind: 'domain', matchField: 'either', normalizedValue: 'spam.example', enabled: true, ...over });
  const sender = (envelope: string | null, header: string | null) => ({
    envelopeFrom: envelope === null ? null : parseAddrSpec(envelope),
    headerFrom: parseSingleMailbox(header),
  });

  it('normalizes owner input to exact values', () => {
    expect(normalizeBlockValue('domain', 'SPAM.example')).toBe('spam.example');
    expect(normalizeBlockValue('address', 'Promo@SPAM.example')).toBe('Promo@spam.example');
    expect(normalizeBlockValue('domain', '*.spam.example')).toBeNull();
  });

  it('matches exact domains only, never suffixes', () => {
    expect(findBlockingRule([rule({})], sender('x@spam.example', null))?.id).toBe('r');
    expect(findBlockingRule([rule({})], sender('x@sub.spam.example', null))).toBeNull();
    expect(findBlockingRule([rule({})], sender('x@notspam.example', null))).toBeNull();
  });

  it('compares address local parts case-sensitively', () => {
    const address = rule({ kind: 'address', normalizedValue: 'Promo@spam.example' });
    expect(findBlockingRule([address], sender('Promo@SPAM.example', null))).not.toBeNull();
    expect(findBlockingRule([address], sender('promo@spam.example', null))).toBeNull();
  });

  it('respects the configured match field', () => {
    const envelopeOnly = rule({ matchField: 'envelope_from' });
    const headerOnly = rule({ matchField: 'header_from' });
    const spoofedHeader = sender('real@ok.example', 'Display <x@spam.example>');
    expect(findBlockingRule([envelopeOnly], spoofedHeader)).toBeNull();
    expect(findBlockingRule([headerOnly], spoofedHeader)).not.toBeNull();
    expect(findBlockingRule([rule({})], spoofedHeader)).not.toBeNull();
  });

  it('cannot be bypassed by a forged header when the envelope matches', () => {
    expect(findBlockingRule([rule({ matchField: 'envelope_from' })], sender('x@spam.example', 'CEO <ceo@trusted.example>'))).not.toBeNull();
  });

  it('ignores disabled rules and malformed headers', () => {
    expect(findBlockingRule([rule({ enabled: false })], sender('x@spam.example', null))).toBeNull();
    expect(findBlockingRule([rule({ matchField: 'header_from' })], sender('ok@ok.example', 'a@spam.example, b@spam.example'))).toBeNull();
  });
});
