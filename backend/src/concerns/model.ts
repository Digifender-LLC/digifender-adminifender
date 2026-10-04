import { HttpError } from '../http/errors';

/** Concern lifecycle (lld.md#concerns). Importance, read, acknowledged and resolution are independent fields. */
export type Status = 'open' | 'waiting' | 'snoozed' | 'resolved';
export type Importance = 'routine' | 'important' | 'urgent' | 'critical';

export const IMPORTANCE_RANK: Readonly<Record<Importance, number>> = { routine: 0, important: 1, urgent: 2, critical: 3 };

export interface ConcernRow {
  id: string;
  revision: number;
  title: string;
  status: Status;
  importance: Importance;
  critical_basis: string | null;
  critical_signal_id: string | null;
  material_digest: string | null;
  due_revision: number;
  waiting_note: string | null;
  awaited_party: string | null;
  follow_up_at: number | null;
  read_at: number | null;
  acknowledged_at: number | null;
  resolved_at: number | null;
  snooze_until: number | null;
  due_at: number | null;
  due_zone: string | null;
  due_source_text: string | null;
  service_id: string | null;
  updated_at: number;
}

export const CONCERN_COLUMNS = [
  'id', 'revision', 'title', 'status', 'importance', 'critical_basis', 'critical_signal_id', 'material_digest', 'due_revision', 'waiting_note',
  'awaited_party', 'follow_up_at', 'read_at', 'acknowledged_at', 'resolved_at', 'snooze_until', 'due_at', 'due_zone', 'due_source_text',
  'service_id', 'updated_at',
] as const satisfies readonly (keyof ConcernRow)[];

/** Fields an undo restores. Importance and evidence membership are not review edits. */
export const LIFECYCLE_FIELDS = [
  'status', 'waiting_note', 'awaited_party', 'follow_up_at', 'acknowledged_at', 'resolved_at', 'snooze_until', 'due_at', 'due_zone', 'due_source_text',
] as const satisfies readonly (keyof ConcernRow)[];

export type Snapshot = Omit<ConcernRow, 'id' | 'revision'>;

export function snapshot(row: ConcernRow): Snapshot {
  const { id: _id, revision: _revision, ...rest } = row;
  return rest;
}

const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

/** API and sync projection of a concern. */
export function concernView(row: ConcernRow, sourceIds: readonly string[]) {
  return {
    id: row.id,
    revision: row.revision,
    title: row.title,
    status: row.status,
    importance: row.importance,
    criticalBasis: row.critical_basis,
    serviceId: row.service_id,
    readAt: iso(row.read_at),
    acknowledgedAt: iso(row.acknowledged_at),
    resolvedAt: iso(row.resolved_at),
    snoozeUntil: iso(row.snooze_until),
    waiting: row.status === 'waiting' ? { note: row.waiting_note, awaitedParty: row.awaited_party, followUpAt: iso(row.follow_up_at) } : null,
    due: row.due_at !== null || row.due_source_text !== null ? { at: iso(row.due_at), zone: row.due_zone, sourceText: row.due_source_text, revision: row.due_revision } : null,
    sourceIds: [...sourceIds],
    updatedAt: iso(row.updated_at),
  };
}

export type ActionKind = 'acknowledge' | 'resolve' | 'reopen' | 'snooze' | 'wait' | 'set_due' | 'mark_read';
export const ACTION_KINDS: readonly ActionKind[] = ['acknowledge', 'resolve', 'reopen', 'snooze', 'wait', 'set_due', 'mark_read'];
/** Review edits that can be undone for 10 minutes at their exact resulting revision. */
export const UNDOABLE: ReadonlySet<string> = new Set(['acknowledge', 'resolve', 'reopen', 'snooze', 'wait', 'set_due']);
export const UNDO_WINDOW_MS = 10 * 60 * 1000;

export type Action =
  | { kind: 'acknowledge' | 'resolve' | 'reopen' | 'mark_read' }
  | { kind: 'snooze'; until: number }
  | { kind: 'wait'; note: string | null; awaitedParty: string | null; followUpAt: number | null }
  | { kind: 'set_due'; at: number | null; zone: string | null; sourceText: string | undefined };

const invalidTransition = (from: Status, kind: ActionKind) =>
  new HttpError(422, 'invalid_transition', 'That action is not available for this item now.', { status: from, kind });

const ALLOWED: Readonly<Record<ActionKind, readonly Status[]>> = {
  acknowledge: ['open', 'waiting', 'snoozed'],
  mark_read: ['open', 'waiting', 'snoozed', 'resolved'],
  resolve: ['open', 'waiting', 'snoozed'],
  reopen: ['waiting', 'snoozed', 'resolved'],
  snooze: ['open', 'waiting', 'snoozed'],
  wait: ['open', 'waiting'],
  set_due: ['open', 'waiting', 'snoozed', 'resolved'],
};

/** Pure state transition for an owner action; the caller persists it with a new revision. */
export function applyAction(row: ConcernRow, action: Action, now: number): ConcernRow {
  if (!ALLOWED[action.kind].includes(row.status)) throw invalidTransition(row.status, action.kind);
  const next: ConcernRow = { ...row, updated_at: now };
  switch (action.kind) {
    case 'acknowledge':
      next.acknowledged_at = now;
      break;
    case 'mark_read':
      next.read_at = now;
      break;
    case 'resolve':
      next.status = 'resolved';
      next.resolved_at = now;
      next.snooze_until = null;
      break;
    case 'reopen':
      next.status = 'open';
      next.resolved_at = null;
      next.snooze_until = null;
      break;
    case 'snooze':
      if (action.until <= now) throw new HttpError(400, 'invalid_request', 'Choose a time in the future.', { path: '$.args.until', reason: 'past' });
      next.status = 'snoozed';
      next.snooze_until = action.until;
      break;
    case 'wait':
      next.status = 'waiting';
      next.waiting_note = action.note;
      next.awaited_party = action.awaitedParty;
      next.follow_up_at = action.followUpAt;
      break;
    case 'set_due':
      next.due_at = action.at;
      next.due_zone = action.zone;
      // The source wording is kept unless the owner supplies new wording.
      if (action.sourceText !== undefined) next.due_source_text = action.sourceText;
      next.due_revision = row.due_revision + 1;
      break;
  }
  return next;
}

/** Restores the lifecycle fields recorded before an event onto the current row. */
export function undoOnto(current: ConcernRow, before: Snapshot, now: number): ConcernRow {
  const next: ConcernRow = { ...current, updated_at: now };
  for (const field of LIFECYCLE_FIELDS) (next as unknown as Record<string, unknown>)[field] = before[field];
  if (next.due_at !== current.due_at || next.due_zone !== current.due_zone || next.due_source_text !== current.due_source_text) next.due_revision = current.due_revision + 1;
  return next;
}

/**
 * Applies new evidence. Material evidence reopens resolved and waiting concerns and clears acknowledgment for the
 * new revision; ordinary snoozes survive unless the evidence is rule-confirmed critical.
 */
export function applyEvidence(row: ConcernRow, update: { importance: Importance; materialDigest: string; confirmedCritical: boolean; criticalBasis: string | null; signalId: string; dueText: string | null; serviceId: string | null }, now: number): { next: ConcernRow; material: boolean } {
  const importance = IMPORTANCE_RANK[update.importance] > IMPORTANCE_RANK[row.importance] ? update.importance : row.importance;
  const next: ConcernRow = {
    ...row,
    importance,
    critical_basis: update.confirmedCritical ? update.criticalBasis : row.critical_basis,
    critical_signal_id: update.confirmedCritical ? update.signalId : row.critical_signal_id,
    due_source_text: row.due_source_text ?? update.dueText,
    service_id: row.service_id ?? update.serviceId,
    material_digest: update.materialDigest,
    updated_at: now,
  };
  const material = update.materialDigest !== row.material_digest;
  if (!material) return { next, material };
  next.acknowledged_at = null;
  if (row.status === 'resolved' || row.status === 'waiting') {
    next.status = 'open';
    next.resolved_at = null;
  } else if (row.status === 'snoozed' && update.confirmedCritical) {
    next.status = 'open';
    next.snooze_until = null;
  }
  return { next, material };
}

/** Owner controls available in a status, shown with the concern so they work without a model. */
export function availableActions(status: Status): ActionKind[] {
  return ACTION_KINDS.filter((kind) => ALLOWED[kind].includes(status));
}
