import { type ParsedAddress, formatAddress, normalizeDomain, parseAddrSpec } from './addresses';

export type BlockKind = 'address' | 'domain';
export type MatchField = 'envelope_from' | 'header_from' | 'either';

export interface BlockRule {
  id: string;
  kind: BlockKind;
  matchField: MatchField;
  normalizedValue: string;
  enabled: boolean;
}

export interface SenderAddresses {
  envelopeFrom: ParsedAddress | null;
  /** Null when the From header is missing, malformed or names several mailboxes. */
  headerFrom: ParsedAddress | null;
}

/** Canonical stored value for a rule, or null when the owner's input is not an exact address/domain. */
export function normalizeBlockValue(kind: BlockKind, raw: string): string | null {
  if (kind === 'domain') return normalizeDomain(raw);
  const parsed = parseAddrSpec(raw);
  return parsed ? formatAddress(parsed) : null;
}

function matches(rule: BlockRule, address: ParsedAddress | null): boolean {
  if (!address) return false;
  return rule.kind === 'domain' ? address.domain === rule.normalizedValue : formatAddress(address) === rule.normalizedValue;
}

/**
 * First enabled rule matching the sender, in the order given (callers pass a stable order).
 * Manual blocks win over every trust hint, so nothing else is consulted here.
 */
export function findBlockingRule(rules: readonly BlockRule[], sender: SenderAddresses): BlockRule | null {
  for (const rule of rules) {
    if (!rule.enabled) continue;
    const envelope = rule.matchField !== 'header_from' && matches(rule, sender.envelopeFrom);
    const header = rule.matchField !== 'envelope_from' && matches(rule, sender.headerFrom);
    if (envelope || header) return rule;
  }
  return null;
}
