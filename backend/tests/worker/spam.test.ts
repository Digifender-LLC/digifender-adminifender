import { describe, expect, it } from 'vitest';
import { type SpamInput, scoreSpam } from '../../src/admission/spam';
import { linkFacts } from '../../src/archive/links';

const base: SpamInput = {
  text: 'Synthetic ordinary message.',
  malformedFrom: false,
  linkHosts: new Set(),
  linkDomainMismatch: false,
  ownerQuarantineOverride: false,
  authentication: { spf: 'unknown', dkim: 'unknown' },
  confirmedSpamBody: false,
  trustedAuthenticatedSender: false,
};

describe('spam-rules-v1', () => {
  it('admits ordinary mail, including urgent wording and unknown authentication', () => {
    expect(scoreSpam({ ...base, text: 'URGENT: production is down, act now.' })).toEqual({ detector: 'spam-rules-v1', score: 0, features: [], quarantine: false });
  });

  it('needs three distinct phrases for the phrase feature', () => {
    const two = scoreSpam({ ...base, text: 'Free money! Lottery winner! free money again' });
    expect(two.features).toEqual([]);
    const three = scoreSpam({ ...base, text: 'Free   money, lottery winner, claim your prize' });
    expect(three.features).toEqual(['promotional_phrases']);
    expect(three.quarantine).toBe(false);
  });

  it('quarantines at the threshold of 3', () => {
    const verdict = scoreSpam({ ...base, malformedFrom: true, linkDomainMismatch: true });
    expect(verdict).toMatchObject({ score: 3, quarantine: true, features: ['malformed_from', 'link_domain_mismatch'] });
    expect(scoreSpam({ ...base, malformedFrom: true }).quarantine).toBe(false);
  });

  it('counts SPF and DKIM only when both explicitly fail', () => {
    expect(scoreSpam({ ...base, authentication: { spf: 'fail', dkim: 'unknown' } }).score).toBe(0);
    expect(scoreSpam({ ...base, authentication: { spf: 'fail', dkim: 'fail' } }).features).toEqual(['spf_and_dkim_failed']);
  });

  it('counts more than five distinct link domains', () => {
    expect(scoreSpam({ ...base, linkHosts: new Set(['a.ex', 'b.ex', 'c.ex', 'd.ex', 'e.ex']) }).score).toBe(0);
    expect(scoreSpam({ ...base, linkHosts: new Set(['a.ex', 'b.ex', 'c.ex', 'd.ex', 'e.ex', 'f.ex']) }).score).toBe(1);
  });

  it('applies an owner quarantine override even for trusted senders', () => {
    expect(scoreSpam({ ...base, ownerQuarantineOverride: true, trustedAuthenticatedSender: true })).toMatchObject({ score: 100, quarantine: true });
  });

  it('lets an authenticated trust override bypass heuristic suspicion only', () => {
    expect(scoreSpam({ ...base, malformedFrom: true, linkDomainMismatch: true, trustedAuthenticatedSender: true }).quarantine).toBe(false);
  });

  it('scans only the first 64 KiB of text', () => {
    const late = `${'x '.repeat(40_000)} free money lottery winner claim your prize`;
    expect(scoreSpam({ ...base, text: late }).features).toEqual([]);
  });
});

describe('link facts', () => {
  it('detects a visible domain that differs from the real href host', () => {
    expect(linkFacts('<a href="https://evil.example/login">bank.example</a>', '').mismatchedLiteralDomain).toBe(true);
    expect(linkFacts('<a href="https://www.bank.example/login">bank.example</a>', '').mismatchedLiteralDomain).toBe(false);
    expect(linkFacts('<a href="https://evil.example/login">Sign in</a>', '').mismatchedLiteralDomain).toBe(false);
  });

  it('collects distinct http hosts from HTML and text', () => {
    const facts = linkFacts('<a href="https://a.example/x">a</a><a href="mailto:x@b.example">b</a>', 'see http://c.example/y and https://a.example/z');
    expect([...facts.hosts].sort()).toEqual(['a.example', 'c.example']);
  });
});
