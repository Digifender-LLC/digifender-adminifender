import { authenticate } from '../auth/sessions';
import { loadConcern, writeConcern } from '../concerns/store';
import type { ConcernRow, Importance } from '../concerns/model';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { WithStatus } from '../http/status';
import { v } from '../http/validate';
import { reportsEnabled } from './job';
import { requireReports } from './routes';

/**
 * Report obligations (lld.md#reports, lld.md#operations): missing heartbeats and dated readiness items become ordinary
 * concerns. A missing report means missing evidence, never proof that a service is down.
 */

export const DEFAULT_INTERVAL_MS = 24 * 60 * 60 * 1000;
export const DEFAULT_GRACE_MS = 60 * 60 * 1000;
/** Readiness items due within this window get a reminder concern. */
export const READINESS_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

export function systemConcern(id: string, title: string, importance: Importance, serviceId: string, now: number, due?: { at: number; text: string }): ConcernRow {
  return {
    id,
    revision: 1,
    title: title.slice(0, 200),
    status: 'open',
    importance,
    critical_basis: null,
    critical_signal_id: null,
    material_digest: null,
    // A dated obligation rides on the ordinary due reminder (FR-030).
    due_revision: due ? 1 : 0,
    waiting_note: null,
    awaited_party: null,
    follow_up_at: null,
    read_at: null,
    acknowledged_at: null,
    resolved_at: null,
    snooze_until: null,
    due_at: due?.at ?? null,
    due_zone: due ? 'UTC' : null,
    due_source_text: due?.text ?? null,
    service_id: serviceId,
    updated_at: now,
  };
}

const iso = (ms: number) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');

interface ExpectationRow {
  service_id: string;
  revision: number;
  interval_ms: number;
  grace_ms: number;
  last_sequence: number | null;
  last_received: number | null;
  concern_id: string | null;
  concern_revision: number | null;
  created_at: number | null;
}

/** Scheduled pass: one concern per service and expectation revision once a heartbeat is overdue. */
export async function evaluateHeartbeats(env: Env, now: number): Promise<number> {
  if (!reportsEnabled(env)) return 0;
  const db = env.CONTROL_DB;
  const { results } = await db
    .prepare('SELECT e.*, s.name FROM heartbeat_expectations e JOIN service_catalog s ON s.id = e.service_id')
    .all<ExpectationRow & { name: string }>();
  let raised = 0;
  for (const e of results) {
    const since = e.last_received ?? e.created_at ?? now;
    if (now <= since + e.interval_ms + e.grace_ms || e.concern_revision === e.revision) continue;
    const id = crypto.randomUUID();
    const hours = Math.round(e.interval_ms / 3_600_000);
    const title = e.last_received === null ? `No heartbeat received from ${e.name} yet (expected every ${hours} h)` : `No heartbeat received from ${e.name} since ${iso(e.last_received)}`;
    const guard = { sql: 'NOT EXISTS (SELECT 1 FROM heartbeat_expectations WHERE service_id = ? AND concern_revision = ?)', params: [e.service_id, e.revision] };
    await db.batch([
      ...writeConcern(db, { before: null, after: systemConcern(id, title, 'important', e.service_id, now), kind: 'created', eventId: crypto.randomUUID(), sourceIds: [], now, meaningful: false, guard }),
      db.prepare('UPDATE heartbeat_expectations SET concern_id = ?, concern_revision = revision WHERE service_id = ? AND revision = ? AND concern_revision IS NOT revision').bind(id, e.service_id, e.revision),
    ]);
    raised += 1;
  }
  return raised;
}

/**
 * Statements for a trusted heartbeat in the report job's batch. Only a newer sequence moves the last-seen time, so a
 * late or replayed heartbeat cannot erase newer missing-evidence state; the tracking concern then says it resumed.
 */
export async function heartbeatStatements(db: D1Database, serviceId: string, sequence: number, receivedAt: number, now: number, guard: { sql: string; params: unknown[] }): Promise<D1PreparedStatement[]> {
  const e = await db.prepare('SELECT * FROM heartbeat_expectations WHERE service_id = ?').bind(serviceId).first<ExpectationRow>();
  if (!e || (e.last_sequence !== null && sequence <= e.last_sequence)) return [];
  const statements = [
    db.prepare(`UPDATE heartbeat_expectations SET last_sequence = ?, last_received = ? WHERE service_id = ? AND (last_sequence IS NULL OR last_sequence < ?) AND ${guard.sql}`)
      .bind(sequence, receivedAt, serviceId, sequence, ...guard.params),
  ];
  if (e.concern_id) {
    const { row, sourceIds } = await loadConcern(db, e.concern_id).catch(() => ({ row: null, sourceIds: [] as string[] }));
    if (row && row.status !== 'resolved' && !row.title.startsWith('Heartbeat resumed')) {
      const name = (await db.prepare('SELECT name FROM service_catalog WHERE id = ?').bind(serviceId).first<{ name: string }>())?.name ?? 'service';
      const after: ConcernRow = { ...row, revision: row.revision + 1, title: `Heartbeat resumed from ${name} at ${iso(receivedAt)}`, importance: 'routine', updated_at: now };
      statements.push(...writeConcern(db, { before: row, after, kind: 'evidence_added', eventId: crypto.randomUUID(), sourceIds, now, meaningful: false, guard }));
    }
  }
  return statements;
}

const READINESS_LABEL: Record<string, string> = {
  domain_expiry: 'Domain renewal',
  credential_reconnect: 'Credential reconnect',
  credential_revocation: 'Credential revocation',
  backup_verification: 'Backup verification',
  recovery_drill: 'Recovery drill',
};

/** Scheduled pass: a reminder concern for each reported readiness item with a known due date inside the window. */
export async function evaluateReadiness(env: Env, now: number): Promise<number> {
  if (!reportsEnabled(env)) return 0;
  const db = env.CONTROL_DB;
  const { results } = await db
    .prepare(
      `SELECT r.service_id, r.report_json, r.source_id FROM service_report_heads h JOIN service_reports r ON r.service_id = h.service_id AND r.event_id = h.event_id
       WHERE h.kind = 'readiness'`,
    )
    .all<{ service_id: string; report_json: string; source_id: string }>();
  let raised = 0;
  for (const head of results) {
    const facts = (JSON.parse(head.report_json) as { facts: { resourceRef: string; kind: string; dueAt?: string | null; evidenceAt: string } }).facts;
    // No synthesized dates: an item without a reported due date stays "unknown" in the overview.
    if (!facts.dueAt) continue;
    const due = Date.parse(facts.dueAt);
    if (due - now > READINESS_WINDOW_MS) continue;
    const known = await db.prepare('SELECT 1 AS hit FROM readiness_concerns WHERE service_id = ? AND resource_ref = ? AND kind = ? AND due_at = ?').bind(head.service_id, facts.resourceRef, facts.kind, due).first();
    if (known) continue;
    const id = crypto.randomUUID();
    const label = READINESS_LABEL[facts.kind] ?? 'Readiness item';
    const guard = { sql: 'NOT EXISTS (SELECT 1 FROM readiness_concerns WHERE service_id = ? AND resource_ref = ? AND kind = ? AND due_at = ?)', params: [head.service_id, facts.resourceRef, facts.kind, due] };
    const after = systemConcern(id, `${label} for ${facts.resourceRef} due ${iso(due)} (reported ${facts.evidenceAt})`, 'important', head.service_id, now, { at: due, text: `reported dueAt ${facts.dueAt}` });
    await db.batch([
      ...writeConcern(db, { before: null, after, kind: 'created', eventId: crypto.randomUUID(), sourceIds: [head.source_id], now, meaningful: false, guard }),
      db.prepare('INSERT OR IGNORE INTO readiness_concerns(service_id, resource_ref, kind, due_at, concern_id) VALUES (?, ?, ?, ?, ?)').bind(head.service_id, facts.resourceRef, facts.kind, due, id),
    ]);
    raised += 1;
  }
  return raised;
}

// --- Heartbeat expectation API -----------------------------------------------------------------------------------

const seconds = (min: number, max: number) => v.integer({ min, max });

export const expectationBody = v.object({ serviceId: v.uuid(), intervalSeconds: v.optional(seconds(300, 30 * 86_400)), graceSeconds: v.optional(seconds(0, 7 * 86_400)) });
export const expectationPatch = v.object({ expectedRevision: v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER }), intervalSeconds: v.optional(seconds(300, 30 * 86_400)), graceSeconds: v.optional(seconds(0, 7 * 86_400)) });

const expectationView = (e: ExpectationRow) => ({
  serviceId: e.service_id,
  revision: e.revision,
  intervalSeconds: e.interval_ms / 1000,
  graceSeconds: e.grace_ms / 1000,
  lastReceivedAt: e.last_received === null ? null : new Date(e.last_received).toISOString(),
  concernId: e.concern_id,
});

// GET /heartbeat-expectations
export async function listExpectations(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const { results } = await env.CONTROL_DB.prepare('SELECT * FROM heartbeat_expectations ORDER BY service_id').all<ExpectationRow>();
  return { expectations: results.map(expectationView) };
}

// POST /heartbeat-expectations — defaults 24 h interval and 1 h grace.
export async function createExpectation(env: Env, now: number, body: ReturnType<typeof expectationBody>) {
  requireReports(env);
  const db = env.CONTROL_DB;
  if (!(await db.prepare('SELECT 1 AS hit FROM service_catalog WHERE id = ?').bind(body.serviceId).first())) throw notFound();
  const interval = (body.intervalSeconds ?? DEFAULT_INTERVAL_MS / 1000) * 1000;
  const grace = (body.graceSeconds ?? DEFAULT_GRACE_MS / 1000) * 1000;
  const result = await db.prepare('INSERT OR IGNORE INTO heartbeat_expectations(service_id, revision, interval_ms, grace_ms, created_at) VALUES (?, 1, ?, ?, ?)').bind(body.serviceId, interval, grace, now).run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'already_exists', 'This service already has a heartbeat expectation.');
  return new WithStatus(201, { serviceId: body.serviceId, revision: 1 });
}

// PATCH /heartbeat-expectations/{serviceId} — a new revision may raise its own concern once overdue again.
export async function updateExpectation(env: Env, _now: number, body: ReturnType<typeof expectationPatch>, serviceId: string) {
  requireReports(env);
  const db = env.CONTROL_DB;
  const e = await db.prepare('SELECT * FROM heartbeat_expectations WHERE service_id = ?').bind(serviceId).first<ExpectationRow>();
  if (!e) throw notFound();
  if (e.revision !== body.expectedRevision) throw new HttpError(409, 'stale_revision', 'The expectation changed. Reload it.', { currentRevision: e.revision });
  await db.prepare('UPDATE heartbeat_expectations SET interval_ms = ?, grace_ms = ?, revision = revision + 1 WHERE service_id = ? AND revision = ?')
    .bind((body.intervalSeconds ?? e.interval_ms / 1000) * 1000, (body.graceSeconds ?? e.grace_ms / 1000) * 1000, serviceId, e.revision)
    .run();
  return { serviceId, revision: e.revision + 1 };
}

