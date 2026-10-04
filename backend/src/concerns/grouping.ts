import { parseSingleMailbox } from '../admission/addresses';
import { isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, archiveFor } from '../sources/registry';
import type { Effect, Predicate } from '../triage/rules';
import { type ConcernRow, type Importance, concernView } from './model';
import { loadConcern, writeConcern } from './store';

const stale = (current: number) => new HttpError(409, 'stale_revision', 'This item changed. Reload and try again.', { currentRevision: current });
const expectedRevision = v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER });
const MAX_MERGE = 20;
const MAX_SPLIT = 200;

function uuidList(max: number): Validator<string[]> {
  return (value, path) => {
    if (!Array.isArray(value) || value.length === 0 || value.length > max) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'expected_list' });
    const ids = value.map((item, i) => v.uuid()(item, `${path}[${i}]`));
    if (new Set(ids).size !== ids.length) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'duplicate' });
    return ids;
  };
}

async function commit(db: D1Database, statements: D1PreparedStatement[]): Promise<void> {
  try {
    await db.batch(statements);
  } catch (error) {
    if (isAssertionFailure(error)) throw new HttpError(409, 'stale_revision', 'Something changed. Reload and try again.');
    throw error;
  }
}

const revisionsShape: Validator<{ id: string; expectedRevision: number }[]> = (value, path) => {
  if (!Array.isArray(value) || value.length < 2 || value.length > MAX_MERGE) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'expected_list' });
  const items = value.map((item, i) => v.object({ id: v.uuid(), expectedRevision })(item, `${path}[${i}]`));
  if (new Set(items.map((i) => i.id)).size !== items.length) throw new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason: 'duplicate' });
  return items;
};

export const mergeBody = v.object({ targetId: v.uuid(), concerns: revisionsShape });

/**
 * POST /concerns/merge — every current ID and revision is required. Evidence links move to the target; merged
 * concerns are resolved with a `merged` event naming the target, so history stays inspectable. Nothing is deleted.
 */
export async function mergeConcerns(env: Env, now: number, body: ReturnType<typeof mergeBody>) {
  if (!body.concerns.some((c) => c.id === body.targetId)) throw new HttpError(400, 'invalid_request', 'The target must be one of the listed concerns.', { path: '$.targetId', reason: 'not_listed' });
  const db = env.CONTROL_DB;
  const loaded = await Promise.all(body.concerns.map(async (c) => ({ ...(await loadConcern(db, c.id)), expected: c.expectedRevision })));
  for (const item of loaded) if (item.row.revision !== item.expected) throw stale(item.row.revision);
  const target = loaded.find((l) => l.row.id === body.targetId)!;
  const others = loaded.filter((l) => l.row.id !== body.targetId);
  const allSources = [...target.sourceIds, ...others.flatMap((o) => o.sourceIds)];
  const rank: Record<Importance, number> = { routine: 0, important: 1, urgent: 2, critical: 3 };
  const top = loaded.reduce((best, l) => (rank[l.row.importance] > rank[best.importance] ? l.row : best), target.row);
  const merged: ConcernRow = {
    ...target.row,
    revision: target.row.revision + 1,
    importance: top.importance,
    critical_basis: top.importance === 'critical' ? top.critical_basis : target.row.critical_basis,
    critical_signal_id: top.importance === 'critical' ? top.critical_signal_id : target.row.critical_signal_id,
    updated_at: now,
  };
  const statements = writeConcern(db, {
    before: target.row,
    after: merged,
    kind: 'merged',
    eventId: crypto.randomUUID(),
    sourceIds: allSources,
    now,
    meaningful: true,
    eventDetail: { from: others.map((o) => ({ id: o.row.id, revision: o.row.revision, sourceIds: o.sourceIds })) },
  });
  for (const other of others) {
    const after: ConcernRow = { ...other.row, revision: other.row.revision + 1, status: 'resolved', resolved_at: now, snooze_until: null, updated_at: now };
    statements.push(
      ...writeConcern(db, {
        before: other.row,
        after,
        kind: 'merged_away',
        eventId: crypto.randomUUID(),
        sourceIds: [],
        removedSourceIds: other.sourceIds,
        now,
        meaningful: true,
        eventDetail: { into: target.row.id, sourceIds: other.sourceIds },
      }),
    );
  }
  await commit(db, statements);
  return { concern: concernView(merged, allSources), mergedIds: others.map((o) => o.row.id) };
}

export const splitBody = v.object({ expectedRevision, sourceIds: uuidList(MAX_SPLIT) });

/** POST /concerns/{id}/split — the chosen evidence moves to a new open concern; at least one source must stay. */
export async function splitConcern(env: Env, now: number, body: ReturnType<typeof splitBody>, id: string) {
  const db = env.CONTROL_DB;
  const { row, sourceIds } = await loadConcern(db, id);
  if (row.revision !== body.expectedRevision) throw stale(row.revision);
  const moving = body.sourceIds;
  if (moving.some((s) => !sourceIds.includes(s))) throw new HttpError(422, 'not_member', 'Every source must belong to this concern.');
  const staying = sourceIds.filter((s) => !moving.includes(s));
  if (staying.length === 0) throw new HttpError(422, 'nothing_left', 'Leave at least one source in the original concern.');
  const firstMoved = await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(moving[0]).first<RegistryRow>();
  const subject = firstMoved ? await subjectOf(env, firstMoved) : null;
  const created: ConcernRow = {
    ...row,
    id: crypto.randomUUID(),
    revision: 1,
    title: subject?.trim().slice(0, 200) || row.title,
    status: 'open',
    critical_basis: null,
    critical_signal_id: null,
    importance: row.importance === 'critical' ? 'urgent' : row.importance,
    read_at: null,
    acknowledged_at: null,
    resolved_at: null,
    snooze_until: null,
    waiting_note: null,
    awaited_party: null,
    follow_up_at: null,
    updated_at: now,
  };
  const remaining: ConcernRow = { ...row, revision: row.revision + 1, updated_at: now };
  const statements = [
    ...writeConcern(db, { before: row, after: remaining, kind: 'split', eventId: crypto.randomUUID(), sourceIds: staying, removedSourceIds: moving, now, meaningful: true, eventDetail: { into: created.id, sourceIds: moving } }),
    ...writeConcern(db, { before: null, after: created, kind: 'split_from', eventId: crypto.randomUUID(), sourceIds: moving, now, meaningful: true, eventDetail: { from: row.id } }),
  ];
  await commit(db, statements);
  return { concern: concernView(remaining, staying), created: concernView(created, moving) };
}

async function subjectOf(env: Env, row: RegistryRow): Promise<string | null> {
  const archive = archiveFor(env, row);
  return (await archive?.prepare('SELECT subject FROM sources WHERE id = ?').bind(row.id).first<{ subject: string }>())?.subject ?? null;
}

async function senderOf(env: Env, row: RegistryRow): Promise<{ address: string; domain: string } | null> {
  const archive = archiveFor(env, row);
  const stored = await archive?.prepare('SELECT headers_json FROM sources WHERE id = ?').bind(row.id).first<{ headers_json: string }>();
  if (!stored) return null;
  const froms = (JSON.parse(stored.headers_json) as { name: string; value: string }[]).filter((h) => h.name.toLowerCase() === 'from');
  const mailbox = froms.length === 1 ? parseSingleMailbox(froms[0]?.value ?? null) : null;
  return mailbox ? { address: `${mailbox.local}@${mailbox.domain}`, domain: mailbox.domain } : null;
}

const EFFECT_FOR: Readonly<Record<Importance, Effect>> = { routine: { less_important: true }, important: { surface: true }, urgent: { surface: true }, critical: { critical: true } };

export const correctionBody = v.object({
  expectedRevision,
  importance: v.oneOf(['routine', 'important', 'urgent', 'critical'] as const),
  applyTo: v.oneOf(['this_concern', 'sender', 'domain'] as const),
  reason: v.optional(v.string({ max: 500 })),
});

/**
 * POST /concerns/{id}/corrections — edits this concern's importance and, for sender or domain scope, returns a rule
 * preview only. The rule is saved by a separate POST /rules; blocks and source privacy are never changed here.
 */
export async function correctConcern(env: Env, now: number, body: ReturnType<typeof correctionBody>, id: string) {
  const db = env.CONTROL_DB;
  const { row, sourceIds } = await loadConcern(db, id);
  if (row.revision !== body.expectedRevision) throw stale(row.revision);
  const after: ConcernRow = {
    ...row,
    revision: row.revision + 1,
    importance: body.importance,
    critical_basis: body.importance === 'critical' ? 'owner' : null,
    critical_signal_id: body.importance === 'critical' ? `correction:${row.id}:${row.revision + 1}` : null,
    updated_at: now,
  };
  let rulePreview: { predicate: Predicate; effects: Effect[] } | null = null;
  if (body.applyTo !== 'this_concern') {
    const latest = await db
      .prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} JOIN concern_sources cs ON cs.source_id = r.id WHERE cs.concern_id = ? AND r.state = 'ready' ORDER BY r.received_at DESC, r.id DESC LIMIT 1`)
      .bind(row.id)
      .first<RegistryRow>();
    const sender = latest ? await senderOf(env, latest) : null;
    if (!sender) throw new HttpError(422, 'no_sender', 'This item has no single sender to base a rule on.');
    rulePreview = { predicate: body.applyTo === 'sender' ? { sender_equals: sender.address } : { domain_equals: sender.domain }, effects: [EFFECT_FOR[body.importance]] };
  }
  await commit(
    db,
    writeConcern(db, {
      before: row,
      after,
      kind: 'correction',
      eventId: crypto.randomUUID(),
      sourceIds,
      now,
      meaningful: true,
      eventDetail: { from: row.importance, to: body.importance, applyTo: body.applyTo, ...(body.reason ? { reason: body.reason } : {}) },
    }),
  );
  return { concern: concernView(after, sourceIds), rulePreview };
}
