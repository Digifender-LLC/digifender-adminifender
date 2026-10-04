import { normalizeBlockValue } from '../admission/blocking';
import { HttpError } from '../http/errors';

/** Closed predicate AST (lld.md#triage). No regex or user code. */
export type Predicate =
  | { all: Predicate[] }
  | { any: Predicate[] }
  | { sender_equals: string }
  | { domain_equals: string }
  | { service_equals: string }
  | { subject_contains: string }
  | { body_contains: string }
  | { report_kind: string }
  | { authentication_result: 'pass' | 'fail' | 'unknown' };

export type Effect =
  | { surface: true }
  | { less_important: true }
  | { critical: true }
  | { assign_service: string }
  | { suggest_due: { text: string } };

export const MAX_DEPTH = 4;
export const MAX_PREDICATES = 20;
export const MAX_LITERAL = 200;
const LEAVES = ['sender_equals', 'domain_equals', 'service_equals', 'subject_contains', 'body_contains', 'report_kind', 'authentication_result'] as const;

const invalid = (path: string, reason: string) => new HttpError(400, 'invalid_request', 'The rule is invalid.', { path, reason });

/** Validates and normalizes a predicate tree from untrusted JSON. */
export function parsePredicate(value: unknown, path = '$.predicate'): Predicate {
  let count = 0;
  const walk = (node: unknown, at: string, depth: number): Predicate => {
    if (depth > MAX_DEPTH) throw invalid(at, 'depth');
    if ((count += 1) > MAX_PREDICATES) throw invalid(at, 'too_many_predicates');
    if (typeof node !== 'object' || node === null || Array.isArray(node)) throw invalid(at, 'expected_object');
    const keys = Object.keys(node);
    if (keys.length !== 1) throw invalid(at, 'one_operator');
    const key = keys[0] as string;
    const arg = (node as Record<string, unknown>)[key];
    if (key === 'all' || key === 'any') {
      if (!Array.isArray(arg) || arg.length === 0) throw invalid(`${at}.${key}`, 'expected_list');
      const children = arg.map((child, i) => walk(child, `${at}.${key}[${i}]`, depth + 1));
      return key === 'all' ? { all: children } : { any: children };
    }
    if (!(LEAVES as readonly string[]).includes(key)) throw invalid(`${at}.${key}`, 'unknown_operator');
    if (typeof arg !== 'string' || arg.length === 0 || arg.length > MAX_LITERAL) throw invalid(`${at}.${key}`, 'literal');
    if (key === 'authentication_result') {
      if (!['pass', 'fail', 'unknown'].includes(arg)) throw invalid(`${at}.${key}`, 'unknown_value');
      return { authentication_result: arg as 'pass' | 'fail' | 'unknown' };
    }
    // Same convention as block rules (lld.md#ingestion): exact local part, normalized IDNA domain.
    if (key === 'sender_equals' || key === 'domain_equals') {
      const normalized = normalizeBlockValue(key === 'sender_equals' ? 'address' : 'domain', arg.trim());
      if (!normalized) throw invalid(`${at}.${key}`, 'format');
      return { [key]: normalized } as Predicate;
    }
    return { [key]: arg } as Predicate;
  };
  return walk(value, path, 1);
}

export function parseEffects(value: unknown, path = '$.effects'): Effect[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 5) throw invalid(path, 'expected_list');
  return value.map((effect, i) => {
    const at = `${path}[${i}]`;
    if (typeof effect !== 'object' || effect === null || Object.keys(effect).length !== 1) throw invalid(at, 'one_effect');
    const [key, arg] = Object.entries(effect)[0] as [string, unknown];
    if ((key === 'surface' || key === 'less_important' || key === 'critical') && arg === true) return { [key]: true } as Effect;
    if (key === 'assign_service' && typeof arg === 'string' && /^[0-9a-f-]{36}$/.test(arg)) return { assign_service: arg };
    if (key === 'suggest_due' && typeof arg === 'object' && arg !== null && Object.keys(arg).length === 1) {
      const text = (arg as { text?: unknown }).text;
      if (typeof text === 'string' && text.length > 0 && text.length <= MAX_LITERAL) return { suggest_due: { text } };
    }
    throw invalid(at, 'unknown_effect');
  });
}

export interface RuleFacts {
  /** Single From address (exact local part, normalized domain), or null when missing/malformed. */
  sender: string | null;
  subject: string;
  body: string;
  serviceIds: string[];
  reportKind: string | null;
  authentication: 'pass' | 'fail' | 'unknown';
}

export function matches(predicate: Predicate, facts: RuleFacts): boolean {
  if ('all' in predicate) return predicate.all.every((p) => matches(p, facts));
  if ('any' in predicate) return predicate.any.some((p) => matches(p, facts));
  if ('sender_equals' in predicate) return facts.sender === predicate.sender_equals;
  if ('domain_equals' in predicate) return facts.sender !== null && facts.sender.slice(facts.sender.lastIndexOf('@') + 1) === predicate.domain_equals;
  if ('service_equals' in predicate) return facts.serviceIds.includes(predicate.service_equals);
  if ('subject_contains' in predicate) return facts.subject.toLowerCase().includes(predicate.subject_contains.toLowerCase());
  if ('body_contains' in predicate) return facts.body.toLowerCase().includes(predicate.body_contains.toLowerCase());
  if ('report_kind' in predicate) return facts.reportKind === predicate.report_kind;
  return facts.authentication === predicate.authentication_result;
}

/** Address predicates are more specific than domain predicates; everything else ranks between them. */
function specificity(predicate: Predicate): number {
  if ('all' in predicate || 'any' in predicate) return Math.max(...('all' in predicate ? predicate.all : predicate.any).map(specificity));
  if ('sender_equals' in predicate) return 2;
  if ('domain_equals' in predicate) return 0;
  return 1;
}

export interface Rule {
  id: string;
  origin: 'owner' | 'learned';
  createdAt: number;
  predicate: Predicate;
  effects: Effect[];
}

export type RuleImportance = 'routine' | 'important' | 'critical';

export interface RuleDecision {
  importance: RuleImportance | null;
  /** The rule whose importance effect won, shown to the owner. */
  winningRuleId: string | null;
  matchedRuleIds: string[];
  serviceIds: string[];
  dueText: string | null;
}

const importanceOf = (rule: Rule): RuleImportance | null =>
  rule.effects.some((e) => 'critical' in e) ? 'critical' : rule.effects.some((e) => 'surface' in e) ? 'important' : rule.effects.some((e) => 'less_important' in e) ? 'routine' : null;

/**
 * Deterministic rule resolution: owner rules before learned rules, more specific before less, then ascending
 * creation time and ID; a critical owner rule wins an importance tie at the same rank.
 */
export function decide(rules: readonly Rule[], facts: RuleFacts): RuleDecision {
  const matched = rules.filter((rule) => matches(rule.predicate, facts));
  const rank = (rule: Rule) => [rule.origin === 'owner' ? 0 : 1, -specificity(rule.predicate)] as const;
  const ordered = [...matched].sort((a, b) => {
    const [ao, as] = rank(a);
    const [bo, bs] = rank(b);
    if (ao !== bo) return ao - bo;
    if (as !== bs) return as - bs;
    const ac = a.origin === 'owner' && importanceOf(a) === 'critical' ? 0 : 1;
    const bc = b.origin === 'owner' && importanceOf(b) === 'critical' ? 0 : 1;
    if (ac !== bc) return ac - bc;
    return a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
  });
  // Only owner rules may confirm critical; a learned rule can at most surface.
  const winner = ordered.find((rule) => importanceOf(rule) !== null);
  let importance = winner ? importanceOf(winner) : null;
  if (winner?.origin === 'learned' && importance === 'critical') importance = 'important';
  const services = new Set<string>();
  let dueText: string | null = null;
  for (const rule of ordered) {
    for (const effect of rule.effects) {
      if ('assign_service' in effect) services.add(effect.assign_service);
      if ('suggest_due' in effect && dueText === null) dueText = effect.suggest_due.text;
    }
  }
  return { importance, winningRuleId: winner?.id ?? null, matchedRuleIds: ordered.map((r) => r.id), serviceIds: [...services], dueText };
}
