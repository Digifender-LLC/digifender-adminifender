import { type AuthContext, authenticate, hasRecentAuth } from '../auth/sessions';
import { base64url } from '../auth/tokens';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { WithStatus } from '../http/status';
import { readJson, v } from '../http/validate';
import { sealToken } from '../notifications/push';
import { reportsEnabled } from './job';

/** P2 is off unless the deployment enables it; each producer then still needs its own acceptance gate. */
export function requireReports(env: Env): void {
  if (!reportsEnabled(env)) throw new HttpError(403, 'feature_disabled', 'Service reports are not enabled.');
}

const alias = v.string({ min: 3, max: 320, pattern: /^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$/ });
const target = v.string({ min: 1, max: 100, pattern: /^[A-Za-z0-9._:/-]+$/ });
const list = <T>(item: (value: unknown, path: string) => T, max: number) => v.array(item, { max });
const name = v.string({ min: 1, max: 100 });

interface ServiceRow {
  id: string;
  revision: number;
  name: string;
  aliases_json: string;
  targets_json: string;
  created_at: number;
}

const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

async function aliasTaken(db: D1Database, aliases: string[], exceptId: string | null): Promise<boolean> {
  const { results } = await db.prepare('SELECT id, aliases_json FROM service_catalog').all<{ id: string; aliases_json: string }>();
  return results.some((r) => r.id !== exceptId && (JSON.parse(r.aliases_json) as string[]).some((a) => aliases.includes(a)));
}

// GET /services — names, receiving aliases, allowlisted resource IDs and report keys (never key material).
export async function listServices(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const db = env.CONTROL_DB;
  const services = (await db.prepare('SELECT id, revision, name, aliases_json, targets_json, created_at FROM service_catalog ORDER BY name, id').all<ServiceRow>()).results;
  const keys = (await db.prepare('SELECT id, service_id, alias, created_at, revoked_at FROM report_keys ORDER BY created_at').all<{ id: string; service_id: string; alias: string | null; created_at: number | null; revoked_at: number | null }>()).results;
  return {
    services: services.map((s) => ({
      id: s.id,
      revision: s.revision,
      name: s.name,
      aliases: JSON.parse(s.aliases_json) as string[],
      targets: JSON.parse(s.targets_json) as string[],
      createdAt: iso(s.created_at),
      reportKeys: keys.filter((k) => k.service_id === s.id).map((k) => ({ id: k.id, alias: k.alias, createdAt: iso(k.created_at), revoked: k.revoked_at !== null })),
    })),
  };
}

export const serviceBody = v.object({ name, aliases: list(alias, 10), targets: list(target, 100) });

// POST /services
export async function createService(env: Env, now: number, body: ReturnType<typeof serviceBody>) {
  requireReports(env);
  const aliases = [...new Set(body.aliases)];
  if (await aliasTaken(env.CONTROL_DB, aliases, null)) throw new HttpError(409, 'alias_taken', 'Another service already uses that alias.');
  const id = crypto.randomUUID();
  await env.CONTROL_DB.prepare('INSERT INTO service_catalog(id, revision, name, aliases_json, targets_json, created_at) VALUES (?, 1, ?, ?, ?, ?)')
    .bind(id, body.name, JSON.stringify(aliases), JSON.stringify([...new Set(body.targets)]), now)
    .run();
  return new WithStatus(201, { id, revision: 1 });
}

export const servicePatch = v.object({ expectedRevision: v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER }), name: v.optional(name), aliases: v.optional(list(alias, 10)), targets: v.optional(list(target, 100)) });

// PATCH /services/{id} — revision-checked; an alias still used by an active key cannot be removed.
export async function updateService(env: Env, _now: number, body: ReturnType<typeof servicePatch>, id: string) {
  requireReports(env);
  const db = env.CONTROL_DB;
  const row = await db.prepare('SELECT id, revision, name, aliases_json, targets_json, created_at FROM service_catalog WHERE id = ?').bind(id).first<ServiceRow>();
  if (!row) throw notFound();
  if (row.revision !== body.expectedRevision) throw new HttpError(409, 'stale_revision', 'The service changed. Reload it.', { currentRevision: row.revision });
  const aliases = body.aliases ? [...new Set(body.aliases)] : (JSON.parse(row.aliases_json) as string[]);
  if (body.aliases && (await aliasTaken(db, aliases, id))) throw new HttpError(409, 'alias_taken', 'Another service already uses that alias.');
  const keyed = (await db.prepare('SELECT DISTINCT alias FROM report_keys WHERE service_id = ? AND revoked_at IS NULL').bind(id).all<{ alias: string }>()).results.map((k) => k.alias);
  if (keyed.some((a) => !aliases.includes(a))) throw new HttpError(409, 'alias_in_use', 'Revoke the report keys for that alias first.');
  const result = await db
    .prepare('UPDATE service_catalog SET name = ?, aliases_json = ?, targets_json = ?, revision = revision + 1 WHERE id = ? AND revision = ?')
    .bind(body.name ?? row.name, JSON.stringify(aliases), JSON.stringify(body.targets ? [...new Set(body.targets)] : JSON.parse(row.targets_json)), id, row.revision)
    .run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'stale_revision', 'The service changed. Reload it.');
  return { id, revision: row.revision + 1 };
}

const keyBody = v.object({ alias });

/**
 * POST /services/{id}/report-keys — recent passkey required. Returns the new HMAC key exactly once for the producer's
 * configuration; it is stored only encrypted. Deliberately outside the idempotency store so the secret is never
 * retained in a stored response: a retry creates another key, and an unused one can be revoked.
 */
export async function createReportKey(request: Request, env: Env, now: number, [serviceId]: string[]) {
  const auth: AuthContext = await authenticate(request, env, now);
  requireReports(env);
  if (!hasRecentAuth(auth, now)) throw new HttpError(403, 'recent_auth_required', 'Confirm with your passkey first.');
  if (!env.REPORT_KEY_WRAP || env.REPORT_KEY_WRAP.length < 32) throw new HttpError(503, 'unavailable', 'Report keys are not configured.', {}, false);
  const body = await readJson(request, keyBody);
  const service = await env.CONTROL_DB.prepare('SELECT aliases_json FROM service_catalog WHERE id = ?').bind(serviceId).first<{ aliases_json: string }>();
  if (!service) throw notFound();
  if (!(JSON.parse(service.aliases_json) as string[]).includes(body.alias)) throw new HttpError(422, 'unknown_alias', 'Add the alias to the service first.');
  const id = crypto.randomUUID();
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const secret = btoa(String.fromCharCode(...raw));
  await env.CONTROL_DB.prepare('INSERT INTO report_keys(id, service_id, secret_ref, alias, secret_cipher, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(id, serviceId, 'report_key_wrap:v1', body.alias, await sealToken(env.REPORT_KEY_WRAP, id, secret), now)
    .run();
  return new WithStatus(201, { keyId: id, serviceId, alias: body.alias, secretBase64url: base64url(raw), shownOnce: true });
}

export const revokeBody = v.object({});

// POST /report-keys/{id}/revoke — later reports signed with it are not trusted; earlier ones stay as history.
export async function revokeReportKey(env: Env, now: number, _body: ReturnType<typeof revokeBody>, _auth: AuthContext, id: string) {
  requireReports(env);
  const result = await env.CONTROL_DB.prepare('UPDATE report_keys SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL').bind(now, id).run();
  if (result.meta.changes !== 1) throw notFound();
  return { keyId: id, revoked: true };
}

/** Default report expectation until per-service heartbeat settings exist: 24 hours plus 1 hour grace. */
export const DEFAULT_EXPECTATION_MS = 25 * 60 * 60 * 1000;

/**
 * GET /operations/overview — the latest trusted report per service, kind and concern key, with when it was reported and
 * received, its coverage and freshness, plus verification issues. Freshness reflects report expectations, never an
 * active health probe; a service with no report is `unobserved`, not healthy.
 */
export async function operationsOverview(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  requireReports(env);
  const db = env.CONTROL_DB;
  const services = (await db.prepare('SELECT id, name FROM service_catalog ORDER BY name, id').all<{ id: string; name: string }>()).results;
  const { results: heads } = await db
    .prepare(
      `SELECT r.service_id, r.kind, r.concern_key, r.sequence, r.occurred_at, r.received_at, r.source_id, r.report_json
       FROM service_report_heads h JOIN service_reports r ON r.service_id = h.service_id AND r.event_id = h.event_id
       JOIN source_registry s ON s.id = r.source_id AND s.state NOT IN ('deleting', 'deleted')
       ORDER BY r.service_id, r.kind, r.concern_key`,
    )
    .all<{ service_id: string; kind: string; concern_key: string; sequence: number; occurred_at: number; received_at: number; source_id: string; report_json: string }>();
  const issues = (await db.prepare(
    `SELECT i.source_id, i.service_id, i.code, i.created_at FROM report_issues i JOIN source_registry s ON s.id = i.source_id AND s.state NOT IN ('deleting', 'deleted') ORDER BY i.created_at DESC LIMIT 50`,
  ).all<{ source_id: string; service_id: string | null; code: string; created_at: number }>()).results;
  return {
    services: services.map((s) => {
      const mine = heads.filter((h) => h.service_id === s.id);
      return {
        id: s.id,
        name: s.name,
        freshness: mine.length === 0 ? 'unobserved' : now - Math.max(...mine.map((h) => h.received_at)) <= DEFAULT_EXPECTATION_MS ? 'current' : 'stale',
        reports: mine.map((h) => {
          const report = JSON.parse(h.report_json) as { summary: string; facts: unknown; coverage: unknown; links: unknown };
          return {
            kind: h.kind,
            concernKey: h.concern_key,
            sequence: h.sequence,
            summary: report.summary,
            facts: report.facts,
            coverage: report.coverage,
            links: report.links,
            reportedAt: iso(h.occurred_at),
            receivedAt: iso(h.received_at),
            verifiedSignature: true,
            sourceIds: [h.source_id],
            freshness: now - h.received_at <= DEFAULT_EXPECTATION_MS ? 'current' : 'stale',
          };
        }),
      };
    }),
    // Unverified mail stays archived and quarantined; it never updates the views above.
    verificationIssues: issues.map((i) => ({ sourceId: i.source_id, serviceId: i.service_id, code: i.code, createdAt: iso(i.created_at) })),
  };
}
