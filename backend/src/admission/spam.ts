export const SPAM_DETECTOR = 'spam-rules-v1';
export const QUARANTINE_THRESHOLD = 3;
/** Admission scans at most this much normalized text (lld.md#ingestion); the archive keeps all of it. */
export const SCAN_CHARS = 64 * 1024;

const PHRASES = ['free money', 'guaranteed income', 'lottery winner', 'claim your prize', 'wire transfer urgently', 'risk free investment'];

export type SpamFeature =
  | 'owner_quarantine_override'
  | 'confirmed_spam_body'
  | 'malformed_from'
  | 'promotional_phrases'
  | 'many_link_domains'
  | 'link_domain_mismatch'
  | 'spf_and_dkim_failed';

const WEIGHTS: Record<SpamFeature, number> = {
  owner_quarantine_override: 100,
  confirmed_spam_body: 3,
  malformed_from: 2,
  promotional_phrases: 1,
  many_link_domains: 1,
  link_domain_mismatch: 1,
  spf_and_dkim_failed: 1,
};

export interface SpamInput {
  /** Normalized text; only the first SCAN_CHARS characters are inspected. */
  text: string;
  /** True when the From header is missing, malformed or names several mailboxes. */
  malformedFrom: boolean;
  linkHosts: ReadonlySet<string>;
  linkDomainMismatch: boolean;
  ownerQuarantineOverride: boolean;
  /** Trusted transport authentication; 'unknown' contributes nothing. */
  authentication: { spf: 'pass' | 'fail' | 'unknown'; dkim: 'pass' | 'fail' | 'unknown' };
  /** Normalized body hash matches an owner-confirmed spam sample. */
  confirmedSpamBody: boolean;
  /** Enabled trust override whose sender is authenticated and aligned. */
  trustedAuthenticatedSender: boolean;
}

export interface SpamVerdict {
  detector: typeof SPAM_DETECTOR;
  score: number;
  features: SpamFeature[];
  quarantine: boolean;
}

/** Deterministic admission score with the exact LLD weights. Keywords like "urgent" alone never quarantine. */
export function scoreSpam(input: SpamInput): SpamVerdict {
  const features: SpamFeature[] = [];
  if (input.ownerQuarantineOverride) features.push('owner_quarantine_override');
  if (input.confirmedSpamBody) features.push('confirmed_spam_body');
  if (input.malformedFrom) features.push('malformed_from');
  const scanned = input.text.slice(0, SCAN_CHARS).toLowerCase().replace(/\s+/g, ' ');
  if (PHRASES.filter((phrase) => scanned.includes(phrase)).length >= 3) features.push('promotional_phrases');
  if (input.linkHosts.size > 5) features.push('many_link_domains');
  if (input.linkDomainMismatch) features.push('link_domain_mismatch');
  if (input.authentication.spf === 'fail' && input.authentication.dkim === 'fail') features.push('spf_and_dkim_failed');
  const score = features.reduce((sum, feature) => sum + WEIGHTS[feature], 0);
  // A trust override bypasses heuristic suspicion only; an owner quarantine override still applies.
  const heuristic = input.trustedAuthenticatedSender && !input.ownerQuarantineOverride;
  return { detector: SPAM_DETECTOR, score, features, quarantine: !heuristic && score >= QUARANTINE_THRESHOLD };
}
