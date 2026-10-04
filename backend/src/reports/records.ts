import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { WithStatus } from '../http/status';
import { v } from '../http/validate';
import { requireReports } from './routes';

/**
 * Owner records for operations (lld.md#operations): source-linked costs, reviewed runbooks, the service dependency
 * graph and a release timeline. None of them can dispatch anything; a runbook step only describes an action.
 */

const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());
const revision = v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER });
const stale = (current: number) => new HttpError(409, 'stale_revision', 'This record changed. Reload it.', { currentRevision: current });

async function visibleSourceId(db: D1Database, id: string): Promise<void> {
  if (!(await db.prepare("SELECT 1 AS hit FROM source_registry WHERE id = ? AND state NOT IN ('deleting', 'deleted')").bind(id).first())) throw new HttpError(422, 'unknown_source', 'That email is not in the archive.');
}

async function knownService(db: D1Database, id: string | undefined | null): Promise<void> {
  if (id && !(await db.prepare('SELECT 1 AS hit FROM service_catalog WHERE id = ?').bind(id).first())) throw new HttpError(422, 'unknown_service', 'That service is not in the catalogue.');
}

// --- Cost entries (FR-040) ---------------------------------------------------------------------------------------

interface CostRow {
  id: string;
  revision: number;
  service_id: string | null;
  source_id: string;
  period_start: number | null;
  period_end: number | null;
  currency: string;
  amount_micros: number;
  evidence_kind: string;
  owner_confirmed_at: number | null;
}

const costView = (c: CostRow) => ({
  id: c.id,
  revision: c.revision,
  serviceId: c.service_id,
  sourceId: c.source_id,
  periodStart: iso(c.period_start),
  periodEnd: iso(c.period_end),
  currency: c.currency,
  amountMicros: c.amount_micros,
  evidenceKind: c.evidence_kind,
  // An entry is only a proposal until the owner confirms it; nothing pays, charges or enforces anything.
  ownerConfirmed: c.owner_confirmed_at !== null,
  confirmedAt: iso(c.owner_confirmed_at),
});

export const costBody = v.object({
  sourceId: v.uuid(),
  serviceId: v.optional(v.nullable(v.uuid())),
  periodStart: v.optional(v.nullable(v.instant())),
  periodEnd: v.optional(v.nullable(v.instant())),
  currency: v.string({ min: 3, max: 3, pattern: /^[A-Z]{3}$/ }),
  amountMicros: v.integer({ min: -1e15, max: 1e15 }),
  evidenceKind: v.oneOf(['invoice', 'estimate', 'internal_usage', 'unsettled'] as const),
});

export const costPatch = v.object({ expectedRevision: revision, ownerConfirmed: v.optional(v.boolean()), amountMicros: v.optional(v.integer({ min: -1e15, max: 1e15 })), serviceId: v.optional(v.nullable(v.uuid())) });

// GET /cost-entries
export async function listCosts(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const { results } = await env.CONTROL_DB.prepare(
    "SELECT c.* FROM cost_entries c JOIN source_registry s ON s.id = c.source_id AND s.state NOT IN ('deleting', 'deleted') ORDER BY c.period_start DESC, c.id",
  ).all<CostRow>();
  return { entries: results.map(costView) };
}

// POST /cost-entries — always created as a proposal.
export async function createCost(env: Env, _now: number, body: ReturnType<typeof costBody>) {
  requireReports(env);
  const db = env.CONTROL_DB;
  await visibleSourceId(db, body.sourceId);
  await knownService(db, body.serviceId);
  if (body.periodStart != null && body.periodEnd != null && body.periodEnd < body.periodStart) throw new HttpError(422, 'invalid_period', 'The period ends before it starts.');
  const id = crypto.randomUUID();
  await db.prepare('INSERT INTO cost_entries(id, revision, service_id, source_id, period_start, period_end, currency, amount_micros, evidence_kind, owner_confirmed_at) VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, NULL)')
    .bind(id, body.serviceId ?? null, body.sourceId, body.periodStart ?? null, body.periodEnd ?? null, body.currency, body.amountMicros, body.evidenceKind)
    .run();
  return new WithStatus(201, { id, revision: 1, ownerConfirmed: false });
}

// PATCH /cost-entries/{id} — confirming is an explicit owner change; editing the amount withdraws a confirmation.
export async function updateCost(env: Env, now: number, body: ReturnType<typeof costPatch>, id: string) {
  requireReports(env);
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT * FROM cost_entries WHERE id = ?').bind(id).first<CostRow>();
  if (!row) throw notFound();
  if (row.revision !== body.expectedRevision) throw stale(row.revision);
  await knownService(db, body.serviceId);
  const amountChanged = body.amountMicros !== undefined && body.amountMicros !== row.amount_micros;
  const confirmed = body.ownerConfirmed === undefined ? (amountChanged ? null : row.owner_confirmed_at) : body.ownerConfirmed ? now : null;
  const result = await db
    .prepare('UPDATE cost_entries SET amount_micros = ?, service_id = ?, owner_confirmed_at = ?, revision = revision + 1 WHERE id = ? AND revision = ?')
    .bind(body.amountMicros ?? row.amount_micros, body.serviceId === undefined ? row.service_id : body.serviceId, confirmed, id, row.revision)
    .run();
  if (result.meta.changes !== 1) throw stale(row.revision);
  return costView({ ...row, revision: row.revision + 1, amount_micros: body.amountMicros ?? row.amount_micros, owner_confirmed_at: confirmed, service_id: body.serviceId === undefined ? row.service_id : body.serviceId });
}

// --- Runbooks (FR-042) -------------------------------------------------------------------------------------------

const ACTION_KINDS = ['email.reply', 'cloudflare.dns.upsert', 'cloudflare.dns.delete', 'cloudflare.worker.version.activate', 'whumpo.release.apply', 'whumpo.recovery.execute', 'whumpo.support.respond'] as const;
const step = v.object({ text: v.string({ min: 1, max: 2000 }), actionKind: v.optional(v.oneOf(ACTION_KINDS)) });

interface RunbookRow {
  id: string;
  revision: number;
  service_id: string | null;
  title: string;
  steps_json: string;
  evidence_json: string;
  owner_reviewed_at: number | null;
}

const runbookView = (r: RunbookRow) => ({
  id: r.id,
  revision: r.revision,
  serviceId: r.service_id,
  title: r.title,
  steps: JSON.parse(r.steps_json) as unknown[],
  evidenceRefs: JSON.parse(r.evidence_json) as string[],
  // Unreviewed notes, including model-drafted ones, are never authoritative (FR-042).
  ownerReviewed: r.owner_reviewed_at !== null,
  reviewedAt: iso(r.owner_reviewed_at),
});

export const runbookBody = v.object({ title: v.string({ min: 1, max: 200 }), serviceId: v.optional(v.nullable(v.uuid())), steps: v.array(step, { max: 50 }), evidenceRefs: v.array(v.uuid(), { max: 50 }) });
export const runbookPatch = v.object({
  expectedRevision: revision,
  title: v.optional(v.string({ min: 1, max: 200 })),
  steps: v.optional(v.array(step, { max: 50 })),
  evidenceRefs: v.optional(v.array(v.uuid(), { max: 50 })),
  ownerReviewed: v.optional(v.boolean()),
});

// GET /runbooks
export async function listRunbooks(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const { results } = await env.CONTROL_DB.prepare('SELECT * FROM runbooks ORDER BY title, id').all<RunbookRow>();
  return { runbooks: results.map(runbookView) };
}

// POST /runbooks — starts as an unreviewed draft.
export async function createRunbook(env: Env, _now: number, body: ReturnType<typeof runbookBody>) {
  requireReports(env);
  const db = env.CONTROL_DB;
  await knownService(db, body.serviceId);
  for (const ref of body.evidenceRefs) await visibleSourceId(db, ref);
  const id = crypto.randomUUID();
  await db.prepare('INSERT INTO runbooks(id, revision, service_id, title, steps_json, evidence_json, owner_reviewed_at) VALUES (?, 1, ?, ?, ?, ?, NULL)')
    .bind(id, body.serviceId ?? null, body.title, JSON.stringify(body.steps), JSON.stringify([...new Set(body.evidenceRefs)]))
    .run();
  return new WithStatus(201, { id, revision: 1, ownerReviewed: false });
}

// PATCH /runbooks/{id} — any content change returns the runbook to draft until the owner reviews it again.
export async function updateRunbook(env: Env, now: number, body: ReturnType<typeof runbookPatch>, id: string) {
  requireReports(env);
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT * FROM runbooks WHERE id = ?').bind(id).first<RunbookRow>();
  if (!row) throw notFound();
  if (row.revision !== body.expectedRevision) throw stale(row.revision);
  for (const ref of body.evidenceRefs ?? []) await visibleSourceId(db, ref);
  const contentChanged = body.title !== undefined || body.steps !== undefined || body.evidenceRefs !== undefined;
  const reviewed = body.ownerReviewed === true ? now : body.ownerReviewed === false || contentChanged ? null : row.owner_reviewed_at;
  const next: RunbookRow = {
    ...row,
    revision: row.revision + 1,
    title: body.title ?? row.title,
    steps_json: body.steps ? JSON.stringify(body.steps) : row.steps_json,
    evidence_json: body.evidenceRefs ? JSON.stringify([...new Set(body.evidenceRefs)]) : row.evidence_json,
    owner_reviewed_at: reviewed,
  };
  const result = await db.prepare('UPDATE runbooks SET title = ?, steps_json = ?, evidence_json = ?, owner_reviewed_at = ?, revision = revision + 1 WHERE id = ? AND revision = ?')
    .bind(next.title, next.steps_json, next.evidence_json, next.owner_reviewed_at, id, row.revision)
    .run();
  if (result.meta.changes !== 1) throw stale(row.revision);
  return runbookView(next);
}

// --- Dependencies and timeline (FR-036, FR-038) ------------------------------------------------------------------

export const dependencyBody = v.object({ expectedRevision: revision, childId: v.uuid(), relation: v.oneOf(['depends_on', 'hosted_on', 'deployed_by'] as const), remove: v.optional(v.boolean()) });

/** POST /services/{id}/dependencies — an explicit, revision-checked graph change; cycles are allowed. */
export async function changeDependency(env: Env, _now: number, body: ReturnType<typeof dependencyBody>, parentId: string) {
  requireReports(env);
  const db = env.CONTROL_DB;
  const parent = await db.prepare('SELECT revision FROM service_catalog WHERE id = ?').bind(parentId).first<{ revision: number }>();
  if (!parent) throw notFound();
  if (parent.revision !== body.expectedRevision) throw stale(parent.revision);
  if (parentId === body.childId) throw new HttpError(422, 'self_dependency', 'A service cannot depend on itself.');
  await knownService(db, body.childId);
  await db.batch([
    body.remove
      ? db.prepare('DELETE FROM service_dependencies WHERE parent_id = ? AND child_id = ? AND relation = ?').bind(parentId, body.childId, body.relation)
      : db.prepare('INSERT OR IGNORE INTO service_dependencies(parent_id, child_id, relation) VALUES (?, ?, ?)').bind(parentId, body.childId, body.relation),
    db.prepare('UPDATE service_catalog SET revision = revision + 1 WHERE id = ? AND revision = ?').bind(parentId, parent.revision),
  ]);
  return { serviceId: parentId, revision: parent.revision + 1 };
}

const MAX_DEPTH = 8;

/** Related services in either direction, with a visited set and depth limit because the graph may be cyclic. */
export async function relatedServices(db: D1Database, start: string): Promise<string[]> {
  const edges = (await db.prepare('SELECT parent_id, child_id FROM service_dependencies').all<{ parent_id: string; child_id: string }>()).results;
  const seen = new Set([start]);
  let frontier = [start];
  for (let depth = 0; depth < MAX_DEPTH && frontier.length > 0; depth += 1) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const e of edges) {
        const other = e.parent_id === id ? e.child_id : e.child_id === id ? e.parent_id : null;
        if (other && !seen.has(other)) {
          seen.add(other);
          next.push(other);
        }
      }
    }
    frontier = next;
  }
  return [...seen];
}

/**
 * GET /operations/timeline?serviceId=&windowHours= — for each incident report of the service, the releases of it or
 * related services in the window before it. "Preceded" is a time relation only, never a diagnosis (FR-038).
 */
export async function timeline(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const url = new URL(request.url);
  const serviceId = v.uuid()(url.searchParams.get('serviceId'), '$.serviceId');
  const windowHours = url.searchParams.has('windowHours') ? v.integer({ min: 1, max: 24 * 14 })(Number(url.searchParams.get('windowHours')), '$.windowHours') : 24;
  const db = env.CONTROL_DB;
  const related = await relatedServices(db, serviceId);
  const marks = related.map(() => '?').join(',');
  const { results } = await db
    .prepare(
      `SELECT r.service_id, r.kind, r.event_id, r.occurred_at, r.source_id, r.report_json FROM service_reports r
       JOIN source_registry s ON s.id = r.source_id AND s.state NOT IN ('deleting', 'deleted')
       WHERE r.service_id IN (${marks}) AND r.kind IN ('release', 'maintenance', 'uncertain_operation') ORDER BY r.occurred_at DESC LIMIT 500`,
    )
    .bind(...related)
    .all<{ service_id: string; kind: string; event_id: string; occurred_at: number; source_id: string; report_json: string }>();
  const view = (r: (typeof results)[number]) => {
    const report = JSON.parse(r.report_json) as { summary: string; facts: Record<string, unknown> };
    return { serviceId: r.service_id, kind: r.kind, eventId: r.event_id, occurredAt: iso(r.occurred_at), sourceIds: [r.source_id], summary: report.summary, facts: report.facts };
  };
  const releases = results.filter((r) => r.kind === 'release');
  const incidents = results.filter((r) => r.kind !== 'release' && r.service_id === serviceId);
  return {
    serviceId,
    relatedServiceIds: related,
    windowHours,
    incidents: incidents.map((i) => ({
      ...view(i),
      precededBy: releases.filter((r) => r.occurred_at <= i.occurred_at && i.occurred_at - r.occurred_at <= windowHours * 3_600_000).map(view),
    })),
    note: 'preceded_is_time_only',
  };
}
