import { authenticate } from '../auth/sessions';
import { isAssertionFailure } from '../db/assert';
import type { Env } from '../env';
import { openCursor, sealCursor } from '../http/cursor';
import { HttpError, notFound } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { sha256Hex } from '../ids';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, sourceProjection } from '../sources/registry';
import { archiveMetadata, instant, pageSize, rejectUnknownParams } from '../sources/routes';
import {
  ACTION_KINDS,
  type Action,
  type ActionKind,
  CONCERN_COLUMNS,
  type ConcernRow,
  type Snapshot,
  UNDOABLE,
  UNDO_WINDOW_MS,
  applyAction,
  availableActions,
  concernView,
  undoOnto,
} from './model';
import { loadConcern, writeConcern } from './store';

const stale = (current: number) => new HttpError(409, 'stale_revision', 'This item changed. Reload and try again.', { currentRevision: current });
const invalid = (path: string, reason: string) => new HttpError(400, 'invalid_request', 'The request is invalid.', { path, reason });
const expectedRevision = v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER });

const timeZone: Validator<string> = (value, path) => {
  const zone = v.string({ max: 64, pattern: /^[A-Za-z0-9_+\-/]+$/ })(value, path);
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: zone });
  } catch {
    throw invalid(path, 'time_zone');
  }
  return zone;
};

const ARGS: Readonly<Record<ActionKind, Validator<Record<string, unknown>>>> = {
  acknowledge: v.object({}),
  resolve: v.object({}),
  reopen: v.object({}),
  mark_read: v.object({}),
  snooze: v.object({ until: v.instant() }),
  wait: v.object({ note: v.optional(v.nullable(v.string({ max: 500 }))), awaitedParty: v.optional(v.nullable(v.string({ max: 200 }))), followUpAt: v.optional(v.nullable(v.instant())) }),
  set_due: v.object({ at: v.nullable(v.instant()), zone: v.optional(v.nullable(timeZone)), sourceText: v.optional(v.string({ max: 200 })) }),
};

/** `{expectedRevision, kind, args}` with per-kind argument shapes (lld.md#concerns). */
export const actionBody: Validator<{ expectedRevision: number; action: Action }> = (value, path) => {
  const outer = v.object({ expectedRevision, kind: v.oneOf(ACTION_KINDS), args: v.optional(v.record()) })(value, path);
  const args = ARGS[outer.kind](outer.args ?? {}, `${path}.args`);
  const kind = outer.kind;
  let action: Action;
  if (kind === 'snooze') action = { kind, until: args.until as number };
  else if (kind === 'wait') action = { kind, note: (args.note as string | null | undefined) ?? null, awaitedParty: (args.awaitedParty as string | null | undefined) ?? null, followUpAt: (args.followUpAt as number | null | undefined) ?? null };
  else if (kind === 'set_due') action = { kind, at: args.at as number | null, zone: (args.zone as string | null | undefined) ?? null, sourceText: args.sourceText as string | undefined };
  else action = { kind };
  return { expectedRevision: outer.expectedRevision, action };
};

async function commit(db: D1Database, statements: D1PreparedStatement[], expected: number): Promise<void> {
  try {
    await db.batch(statements);
  } catch (error) {
    // Another writer moved the revision between our read and this batch.
    if (isAssertionFailure(error)) throw stale(expected + 1);
    throw error;
  }
}

// POST /concerns/{id}/actions — mark_read alone does not advance the meaningful-review time.
export async function concernAction(env: Env, now: number, body: ReturnType<typeof actionBody>, id: string) {
  const db = env.CONTROL_DB;
  const { row, sourceIds } = await loadConcern(db, id);
  if (row.revision !== body.expectedRevision) throw stale(row.revision);
  const after = { ...applyAction(row, body.action, now), revision: row.revision + 1 };
  const eventId = crypto.randomUUID();
  await commit(
    db,
    writeConcern(db, { before: row, after, kind: body.action.kind, eventId, sourceIds, now, meaningful: body.action.kind !== 'mark_read' }),
    row.revision,
  );
  return { concern: concernView(after, sourceIds), eventId, undoableUntil: UNDOABLE.has(body.action.kind) ? new Date(now + UNDO_WINDOW_MS).toISOString() : null };
}

export const undoBody = v.object({ eventId: v.uuid(), resultingRevision: expectedRevision });

// POST /concerns/{id}/undo — only the exact resulting revision of a recent review edit; later work conflicts.
export async function undoConcernEvent(env: Env, now: number, body: ReturnType<typeof undoBody>, id: string) {
  const db = env.CONTROL_DB;
  const { row, sourceIds } = await loadConcern(db, id);
  const event = await db.prepare('SELECT id, kind, to_revision, before_json, created_at FROM concern_events WHERE id = ? AND concern_id = ?')
    .bind(body.eventId, id)
    .first<{ id: string; kind: string; to_revision: number; before_json: string; created_at: number }>();
  if (!event || event.to_revision !== body.resultingRevision) throw notFound();
  if (!UNDOABLE.has(event.kind)) throw new HttpError(422, 'not_undoable', 'That change cannot be undone.');
  if (now - event.created_at > UNDO_WINDOW_MS) throw new HttpError(422, 'undo_expired', 'The time to undo that change has passed.');
  if (row.revision !== event.to_revision) throw stale(row.revision);
  const after = { ...undoOnto(row, JSON.parse(event.before_json) as Snapshot, now), revision: row.revision + 1 };
  await commit(
    db,
    writeConcern(db, { before: row, after, kind: 'undo', eventId: crypto.randomUUID(), sourceIds, now, meaningful: true, eventDetail: { undoneEventId: event.id } }),
    row.revision,
  );
  return { concern: concernView(after, sourceIds) };
}

const listFilters = (url: URL) => ({
  status: url.searchParams.has('status') ? v.oneOf(['open', 'waiting', 'snoozed', 'resolved'] as const)(url.searchParams.get('status'), '$.status') : null,
  importance: url.searchParams.has('importance') ? v.oneOf(['routine', 'important', 'urgent', 'critical'] as const)(url.searchParams.get('importance'), '$.importance') : null,
  service: url.searchParams.has('service') ? v.uuid()(url.searchParams.get('service'), '$.service') : null,
  dueBefore: instant(url, 'dueBefore'),
  updatedSince: instant(url, 'updatedSince'),
});

async function sourceIdsFor(db: D1Database, ids: string[]): Promise<Map<string, string[]>> {
  const out = new Map<string, string[]>(ids.map((id) => [id, []]));
  if (ids.length === 0) return out;
  const { results } = await db
    .prepare(
      `SELECT cs.concern_id, cs.source_id FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id
       WHERE cs.concern_id IN (${ids.map(() => '?').join(',')}) AND r.state NOT IN ('deleting', 'deleted') ORDER BY r.received_at, r.id`,
    )
    .bind(...ids)
    .all<{ concern_id: string; source_id: string }>();
  for (const r of results) out.get(r.concern_id)?.push(r.source_id);
  return out;
}

// GET /concerns
export async function listConcerns(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['status', 'importance', 'service', 'dueBefore', 'updatedSince', 'limit', 'cursor']);
  const filters = listFilters(url);
  const limit = pageSize(url);
  const digest = await sha256Hex(JSON.stringify(['concerns', filters]));
  const cursorParam = url.searchParams.get('cursor');
  const position = cursorParam ? await openCursor<{ updatedAt: number; id: string }>(env.CURSOR_SECRET, digest, cursorParam, now) : null;
  const where: string[] = [];
  const params: unknown[] = [];
  if (filters.status) (where.push('status = ?'), params.push(filters.status));
  if (filters.importance) (where.push('importance = ?'), params.push(filters.importance));
  if (filters.service) (where.push('service_id = ?'), params.push(filters.service));
  if (filters.dueBefore !== null) (where.push('due_at IS NOT NULL AND due_at < ?'), params.push(filters.dueBefore));
  if (filters.updatedSince !== null) (where.push('updated_at >= ?'), params.push(filters.updatedSince));
  if (position) (where.push('(updated_at < ? OR (updated_at = ? AND id < ?))'), params.push(position.updatedAt, position.updatedAt, position.id));
  const { results } = await env.CONTROL_DB.prepare(
    `SELECT ${CONCERN_COLUMNS.join(', ')} FROM concerns ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY updated_at DESC, id DESC LIMIT ?`,
  )
    .bind(...params, limit + 1)
    .all<ConcernRow>();
  const page = results.slice(0, limit);
  const sources = await sourceIdsFor(env.CONTROL_DB, page.map((r) => r.id));
  const last = page[page.length - 1];
  const nextCursor = results.length > limit && last ? await sealCursor(env.CURSOR_SECRET, digest, { updatedAt: last.updated_at, id: last.id }, now) : null;
  return { concerns: page.map((row) => concernView(row, sources.get(row.id) ?? [])), nextCursor };
}

// GET /concerns/{id} — evidence, revisions, safe controls and last-reported time.
export async function getConcern(request: Request, env: Env, now: number, [id]: string[]) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const { row, sourceIds } = await loadConcern(db, v.uuid()(id, '$.id'));
  const registry = sourceIds.length
    ? (await db.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id IN (${sourceIds.map(() => '?').join(',')}) ORDER BY r.received_at, r.id`).bind(...sourceIds).all<RegistryRow>()).results
    : [];
  const metadata = await archiveMetadata(env, registry);
  const { results: events } = await db
    .prepare('SELECT id, kind, from_revision, to_revision, created_at FROM concern_events WHERE concern_id = ? ORDER BY to_revision DESC LIMIT 50')
    .bind(row.id)
    .all<{ id: string; kind: string; from_revision: number; to_revision: number; created_at: number }>();
  const triage = sourceIds.length
    ? (await db.prepare(`SELECT id, source_id, source_revision, policy_revision, result_json, provenance_json FROM triage_results WHERE source_id IN (${sourceIds.map(() => '?').join(',')}) ORDER BY created_at, id`).bind(...sourceIds).all<{ id: string; source_id: string; source_revision: number; policy_revision: number; result_json: string; provenance_json: string }>()).results
    : [];
  const lastReported = registry.reduce<number | null>((max, r) => (max === null || r.received_at > max ? r.received_at : max), null);
  return {
    concern: concernView(row, sourceIds),
    controls: availableActions(row.status),
    sources: registry.map((r) => sourceProjection(r, metadata.get(r.id) ?? null)),
    lastReportedAt: lastReported === null ? null : new Date(lastReported).toISOString(),
    triage: triage.map((t) => {
      const result = JSON.parse(t.result_json) as { status: string; rules?: { importance: string | null; winningRuleId: string | null; matchedRuleIds: string[] }; output?: unknown; application?: unknown };
      return { sourceId: t.source_id, status: result.status, ruleImportance: result.rules?.importance ?? null, winningRuleId: result.rules?.winningRuleId ?? null, matchedRuleIds: result.rules?.matchedRuleIds ?? [],
        ...(result.output ? { resultId: t.id, sourceRevision: t.source_revision, policyRevision: t.policy_revision, output: result.output, application: result.application ?? { status: 'pending' }, provenance: JSON.parse(t.provenance_json) } : {}) };
    }),
    events: events.map((e) => ({
      id: e.id,
      kind: e.kind,
      fromRevision: e.from_revision,
      toRevision: e.to_revision,
      createdAt: new Date(e.created_at).toISOString(),
      undoableUntil: UNDOABLE.has(e.kind) && e.to_revision === row.revision && now - e.created_at <= UNDO_WINDOW_MS ? new Date(e.created_at + UNDO_WINDOW_MS).toISOString() : null,
    })),
  };
}
