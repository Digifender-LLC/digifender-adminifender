import { describe, expect, it } from 'vitest';
import { type ConcernRow, applyAction, applyEvidence, availableActions, snapshot, undoOnto } from '../../src/concerns/model';
import { HttpError } from '../../src/http/errors';
import { messageIdOf, threadReferences } from '../../src/triage/job';
import { type Rule, type RuleFacts, decide, parseEffects, parsePredicate } from '../../src/triage/rules';

const facts = (over: Partial<RuleFacts> = {}): RuleFacts => ({
  sender: 'alerts@billing.example',
  subject: 'Invoice overdue',
  body: 'Your invoice is overdue.',
  serviceIds: [],
  reportKind: null,
  authentication: 'unknown',
  ...over,
});
const rule = (id: string, over: Partial<Rule>): Rule => ({ id, origin: 'owner', createdAt: 1, predicate: { domain_equals: 'billing.example' }, effects: [{ surface: true }], ...over });
const reason = (fn: () => unknown) => {
  try {
    fn();
  } catch (error) {
    return error instanceof HttpError ? error.details.reason : 'other';
  }
  return 'none';
};

describe('rule predicates', () => {
  it('accepts the closed AST, keeping the local part exact and normalizing domains', () => {
    expect(parsePredicate({ all: [{ sender_equals: ' Alerts@Billing.Example ' }, { subject_contains: 'Invoice' }] })).toEqual({
      all: [{ sender_equals: 'Alerts@billing.example' }, { subject_contains: 'Invoice' }],
    });
    expect(reason(() => parsePredicate({ sender_equals: 'not an address' }))).toBe('format');
    expect(reason(() => parsePredicate({ domain_equals: '*.example' }))).toBe('format');
  });

  it('rejects unknown operators, regex-like values in unknown keys, depth and size overruns', () => {
    expect(reason(() => parsePredicate({ regex: '.*' }))).toBe('unknown_operator');
    expect(reason(() => parsePredicate({ sender_equals: 'a', domain_equals: 'b' }))).toBe('one_operator');
    expect(reason(() => parsePredicate({ all: [{ all: [{ all: [{ all: [{ sender_equals: 'a' }] }] }] }] }))).toBe('depth');
    expect(reason(() => parsePredicate({ any: Array.from({ length: 20 }, () => ({ subject_contains: 'x' })) }))).toBe('too_many_predicates');
    expect(reason(() => parsePredicate({ subject_contains: 'x'.repeat(201) }))).toBe('literal');
    expect(reason(() => parsePredicate({ authentication_result: 'maybe' }))).toBe('unknown_value');
  });

  it('accepts only the listed effects and never an action', () => {
    expect(parseEffects([{ critical: true }, { suggest_due: { text: 'Friday' } }])).toEqual([{ critical: true }, { suggest_due: { text: 'Friday' } }]);
    expect(reason(() => parseEffects([{ delete_source: true }]))).toBe('unknown_effect');
    expect(reason(() => parseEffects([]))).toBe('expected_list');
  });

  it('matches domains exactly, not subdomains', () => {
    const r = [rule('a', {})];
    expect(decide(r, facts()).importance).toBe('important');
    expect(decide(r, facts({ sender: 'x@eu.billing.example' })).importance).toBeNull();
    expect(decide(r, facts({ sender: null })).importance).toBeNull();
  });
});

describe('rule resolution', () => {
  it('prefers owner over learned and address over domain, and shows the winner', () => {
    const rules = [
      rule('learned', { origin: 'learned', predicate: { sender_equals: 'alerts@billing.example' }, effects: [{ less_important: true }] }),
      rule('domain', { createdAt: 5, effects: [{ surface: true }] }),
      rule('address', { createdAt: 9, predicate: { sender_equals: 'alerts@billing.example' }, effects: [{ less_important: true }] }),
    ];
    const decision = decide(rules, facts());
    expect(decision).toMatchObject({ importance: 'routine', winningRuleId: 'address' });
    expect(decision.matchedRuleIds).toEqual(['address', 'domain', 'learned']);
  });

  it('breaks ties by creation, except that a critical owner rule wins importance', () => {
    expect(decide([rule('b', { createdAt: 2 }), rule('a', { createdAt: 1, effects: [{ less_important: true }] })], facts()).winningRuleId).toBe('a');
    expect(decide([rule('a', { createdAt: 1, effects: [{ less_important: true }] }), rule('c', { createdAt: 3, effects: [{ critical: true }] })], facts())).toMatchObject({ importance: 'critical', winningRuleId: 'c' });
  });

  it('never lets a learned rule confirm critical', () => {
    expect(decide([rule('l', { origin: 'learned', effects: [{ critical: true }] })], facts()).importance).toBe('important');
  });
});

describe('thread references', () => {
  it('orders the direct parent first and bounds the list', () => {
    expect(threadReferences('<p@x>', '<root@x> <mid@x> <p@x>')).toEqual(['<p@x>', '<mid@x>', '<root@x>']);
    expect(threadReferences(null, Array.from({ length: 80 }, (_, i) => `<m${i}@x>`).join(' '))).toHaveLength(50);
    expect(messageIdOf(' <id@host> ')).toBe('<id@host>');
    expect(messageIdOf(null)).toBeNull();
  });
});

const NOW = Date.UTC(2026, 9, 3, 12);
const concern = (over: Partial<ConcernRow> = {}): ConcernRow => ({
  id: 'c',
  revision: 1,
  title: 't',
  status: 'open',
  importance: 'routine',
  critical_basis: null,
  critical_signal_id: null,
  material_digest: 'd1',
  due_revision: 0,
  waiting_note: null,
  awaited_party: null,
  follow_up_at: null,
  read_at: null,
  acknowledged_at: null,
  resolved_at: null,
  snooze_until: null,
  due_at: null,
  due_zone: null,
  due_source_text: 'by Friday',
  service_id: null,
  updated_at: 0,
  ...over,
});
const evidence = (over: Partial<Parameters<typeof applyEvidence>[1]> = {}) => ({
  importance: 'routine' as const,
  materialDigest: 'd2',
  confirmedCritical: false,
  criticalBasis: null,
  signalId: 'source:s2',
  dueText: null,
  serviceId: null,
  ...over,
});

describe('concern lifecycle', () => {
  it('keeps read, acknowledged and resolved independent', () => {
    const read = applyAction(concern(), { kind: 'mark_read' }, NOW);
    expect(read).toMatchObject({ status: 'open', read_at: NOW, acknowledged_at: null });
    const acknowledged = applyAction(read, { kind: 'acknowledge' }, NOW);
    expect(acknowledged).toMatchObject({ status: 'open', acknowledged_at: NOW, resolved_at: null });
  });

  it('follows the state diagram', () => {
    expect(availableActions('resolved')).toEqual(['reopen', 'set_due', 'mark_read']);
    const waiting = applyAction(concern(), { kind: 'wait', note: 'Vendor reply', awaitedParty: 'Vendor', followUpAt: NOW + 86_400_000 }, NOW);
    expect(waiting).toMatchObject({ status: 'waiting', waiting_note: 'Vendor reply', awaited_party: 'Vendor', follow_up_at: NOW + 86_400_000, due_at: null });
    const snoozed = applyAction(waiting, { kind: 'snooze', until: NOW + 1000 }, NOW);
    expect(snoozed).toMatchObject({ status: 'snoozed', snooze_until: NOW + 1000 });
    expect(() => applyAction(snoozed, { kind: 'wait', note: null, awaitedParty: null, followUpAt: null }, NOW)).toThrow(HttpError);
    const resolved = applyAction(snoozed, { kind: 'resolve' }, NOW);
    expect(resolved).toMatchObject({ status: 'resolved', resolved_at: NOW, snooze_until: null });
    expect(() => applyAction(resolved, { kind: 'resolve' }, NOW)).toThrow(HttpError);
    expect(applyAction(resolved, { kind: 'reopen' }, NOW)).toMatchObject({ status: 'open', resolved_at: null });
  });

  it('rejects a snooze that is not in the future', () => {
    expect(reason(() => applyAction(concern(), { kind: 'snooze', until: NOW }, NOW))).toBe('past');
  });

  it('keeps the source wording and zone when setting a due date', () => {
    const due = applyAction(concern(), { kind: 'set_due', at: NOW, zone: 'Europe/Berlin', sourceText: undefined }, NOW);
    expect(due).toMatchObject({ due_at: NOW, due_zone: 'Europe/Berlin', due_source_text: 'by Friday', due_revision: 1 });
  });

  it('undo restores lifecycle fields but not importance', () => {
    const before = concern({ importance: 'important' });
    const resolved = { ...applyAction(before, { kind: 'resolve' }, NOW), importance: 'urgent' as const };
    const undone = undoOnto(resolved, snapshot(before), NOW + 1);
    expect(undone).toMatchObject({ status: 'open', resolved_at: null, importance: 'urgent' });
  });
});

describe('new evidence', () => {
  it('reopens resolved and waiting concerns and clears acknowledgment for the new revision', () => {
    for (const status of ['resolved', 'waiting'] as const) {
      const { next, material } = applyEvidence(concern({ status, resolved_at: 1, acknowledged_at: 1 }), evidence(), NOW);
      expect(material).toBe(true);
      expect(next).toMatchObject({ status: 'open', resolved_at: null, acknowledged_at: null });
    }
  });

  it('keeps an ordinary snooze, but a rule-confirmed critical signal resurfaces it', () => {
    const snoozed = concern({ status: 'snoozed', snooze_until: NOW + 1000 });
    expect(applyEvidence(snoozed, evidence(), NOW).next).toMatchObject({ status: 'snoozed', snooze_until: NOW + 1000 });
    expect(applyEvidence(snoozed, evidence({ importance: 'critical', confirmedCritical: true, criticalBasis: 'rule:r' }), NOW).next).toMatchObject({
      status: 'open',
      snooze_until: null,
      importance: 'critical',
      critical_basis: 'rule:r',
      critical_signal_id: 'source:s2',
    });
  });

  it('does not reopen on non-material evidence and never lowers importance', () => {
    const resolved = concern({ status: 'resolved', resolved_at: 1, importance: 'urgent' });
    const { next, material } = applyEvidence(resolved, evidence({ materialDigest: 'd1' }), NOW);
    expect(material).toBe(false);
    expect(next).toMatchObject({ status: 'resolved', importance: 'urgent' });
  });
});
