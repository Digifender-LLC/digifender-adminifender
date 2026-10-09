import { revertOrphans } from '../extraction/device';
import { extractionJobStatements } from '../extraction/job';
import { triageJobStatements } from '../triage/job';
import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { v } from '../http/validate';
import { REGISTRY_COLUMNS, REGISTRY_FROM, type RegistryRow, sourceChange, sourceProjection, visibleSource } from '../sources/registry';
import { type BlockKind, type MatchField, normalizeBlockValue } from './blocking';

const kind = v.oneOf(['address', 'domain'] as const);
const matchField = v.oneOf(['envelope_from', 'header_from', 'either'] as const);
const expectedRevision = v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER });

const stale = (current: number) => new HttpError(409, 'stale_revision', 'This item changed. Reload and try again.', { currentRevision: current });

function exactValue(k: BlockKind, raw: string): string {
  const value = normalizeBlockValue(k, raw);
  if (!value) throw new HttpError(400, 'invalid_request', 'Enter an exact address or domain.', { path: '$.value', reason: 'format' });
  return value;
}

/** Plain statement of what a block rule does, shown before creation (lld.md#ingestion). */
function describeBlock(k: BlockKind, field: MatchField, value: string): string {
  const where = field === 'either' ? 'the envelope sender or the From header' : field === 'envelope_from' ? 'the envelope sender' : 'the From header';
  const what = k === 'domain' ? `exactly the domain ${value} (not its subdomains)` : `exactly the address ${value}`;
  return `Mail whose ${where} is ${what} is rejected before storage. Only daily counts are kept.`;
}

interface BlockRow {
  id: string;
  revision: number;
  kind: BlockKind;
  match_field: MatchField;
  normalized_value: string;
  enabled: number;
  created_at: number;
}

const blockView = (r: BlockRow) => ({
  id: r.id,
  revision: r.revision,
  kind: r.kind,
  matchField: r.match_field,
  value: r.normalized_value,
  enabled: r.enabled === 1,
  effect: describeBlock(r.kind, r.match_field, r.normalized_value),
  createdAt: new Date(r.created_at).toISOString(),
});

export const blockRuleBody = v.object({ kind, matchField, value: v.string({ max: 320 }) });

// GET /block-rules
export async function listBlockRules(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const { results } = await env.CONTROL_DB.prepare('SELECT * FROM block_rules ORDER BY created_at, id').all<BlockRow>();
  const counts = await env.CONTROL_DB.prepare('SELECT rule_id, SUM(count) AS n FROM block_counters WHERE day >= ? GROUP BY rule_id')
    .bind(new Date(now - 30 * 86_400_000).toISOString().slice(0, 10))
    .all<{ rule_id: string; n: number }>();
  const recent = new Map(counts.results.map((c) => [c.rule_id, c.n]));
  return { rules: results.map((r) => ({ ...blockView(r), rejectedLast30Days: recent.get(r.id) ?? 0 })) };
}

// POST /block-rules
export async function createBlockRule(env: Env, now: number, body: ReturnType<typeof blockRuleBody>) {
  const value = exactValue(body.kind, body.value);
  const id = crypto.randomUUID();
  try {
    await env.CONTROL_DB.prepare('INSERT INTO block_rules(id, revision, kind, match_field, normalized_value, enabled, created_at) VALUES (?, 1, ?, ?, ?, 1, ?)')
      .bind(id, body.kind, body.matchField, value, now)
      .run();
  } catch {
    throw new HttpError(409, 'duplicate_rule', 'That rule already exists.');
  }
  return blockView({ id, revision: 1, kind: body.kind, match_field: body.matchField, normalized_value: value, enabled: 1, created_at: now });
}

export const blockRulePatch = v.object({ expectedRevision, enabled: v.optional(v.boolean()), kind: v.optional(kind), matchField: v.optional(matchField), value: v.optional(v.string({ max: 320 })) });

// PATCH /block-rules/{id}
export async function updateBlockRule(env: Env, _now: number, body: ReturnType<typeof blockRulePatch>, id: string) {
  const current = await env.CONTROL_DB.prepare('SELECT * FROM block_rules WHERE id = ?').bind(id).first<BlockRow>();
  if (!current) throw notFound();
  if (current.revision !== body.expectedRevision) throw stale(current.revision);
  const nextKind = body.kind ?? current.kind;
  const next = {
    ...current,
    kind: nextKind,
    match_field: body.matchField ?? current.match_field,
    normalized_value: body.value !== undefined ? exactValue(nextKind, body.value) : body.kind ? exactValue(nextKind, current.normalized_value) : current.normalized_value,
    enabled: body.enabled === undefined ? current.enabled : body.enabled ? 1 : 0,
    revision: current.revision + 1,
  };
  let changes = 0;
  try {
    const result = await env.CONTROL_DB.prepare('UPDATE block_rules SET revision = ?, kind = ?, match_field = ?, normalized_value = ?, enabled = ? WHERE id = ? AND revision = ?')
      .bind(next.revision, next.kind, next.match_field, next.normalized_value, next.enabled, id, current.revision)
      .run();
    changes = result.meta.changes;
  } catch {
    throw new HttpError(409, 'duplicate_rule', 'That rule already exists.');
  }
  if (changes !== 1) throw stale(current.revision + 1);
  return blockView(next);
}

interface OverrideRow {
  id: string;
  revision: number;
  kind: BlockKind;
  normalized_value: string;
  verdict: 'quarantine' | 'trust';
  enabled: number;
}
const overrideView = (r: OverrideRow) => ({ id: r.id, revision: r.revision, kind: r.kind, value: r.normalized_value, verdict: r.verdict, enabled: r.enabled === 1 });

// GET /spam-overrides
export async function listSpamOverrides(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const { results } = await env.CONTROL_DB.prepare('SELECT * FROM spam_overrides ORDER BY kind, normalized_value').all<OverrideRow>();
  return { overrides: results.map(overrideView) };
}

export const spamOverrideBody = v.object({ kind, value: v.string({ max: 320 }), verdict: v.oneOf(['quarantine', 'trust'] as const) });

// POST /spam-overrides — changing a rule never releases existing quarantine.
export async function createSpamOverride(env: Env, _now: number, body: ReturnType<typeof spamOverrideBody>) {
  const value = exactValue(body.kind, body.value);
  const id = crypto.randomUUID();
  try {
    await env.CONTROL_DB.prepare('INSERT INTO spam_overrides(id, revision, kind, normalized_value, verdict, enabled) VALUES (?, 1, ?, ?, ?, 1)').bind(id, body.kind, value, body.verdict).run();
  } catch {
    throw new HttpError(409, 'duplicate_rule', 'That override already exists.');
  }
  return overrideView({ id, revision: 1, kind: body.kind, normalized_value: value, verdict: body.verdict, enabled: 1 });
}

export const spamOverridePatch = v.object({ expectedRevision, enabled: v.optional(v.boolean()), verdict: v.optional(v.oneOf(['quarantine', 'trust'] as const)) });

// PATCH /spam-overrides/{id}
export async function updateSpamOverride(env: Env, _now: number, body: ReturnType<typeof spamOverridePatch>, id: string) {
  const current = await env.CONTROL_DB.prepare('SELECT * FROM spam_overrides WHERE id = ?').bind(id).first<OverrideRow>();
  if (!current) throw notFound();
  if (current.revision !== body.expectedRevision) throw stale(current.revision);
  const next = { ...current, revision: current.revision + 1, verdict: body.verdict ?? current.verdict, enabled: body.enabled === undefined ? current.enabled : body.enabled ? 1 : 0 };
  const result = await env.CONTROL_DB.prepare('UPDATE spam_overrides SET revision = ?, verdict = ?, enabled = ? WHERE id = ? AND revision = ?')
    .bind(next.revision, next.verdict, next.enabled, id, current.revision)
    .run();
  if (result.meta.changes !== 1) throw stale(current.revision + 1);
  return overrideView(next);
}

export const quarantineBody = v.object({ expectedRevision, reason: v.optional(v.string({ max: 500 })) });
export const releaseBody = v.object({ expectedRevision });

async function setAdmission(env: Env, now: number, id: string, revision: number, admission: 'normal' | 'quarantine') {
  const row = await visibleSource(env, id);
  if (row.revision !== revision) throw stale(row.revision);
  if (row.state !== 'ready') throw new HttpError(422, 'not_ready', 'The source has not finished processing.');
  if (row.admission === admission) return sourceProjection(row);
  const next: RegistryRow = { ...row, admission, revision: row.revision + 1 };
  const db = env.CONTROL_DB;
  const statements = [
    db.prepare("UPDATE source_registry SET admission = ?, revision = revision + 1 WHERE id = ? AND revision = ? AND state = 'ready'").bind(admission, id, revision),
    sourceChange(db, id, sourceProjection(next), now, { sql: 'revision = ? AND admission = ?', params: [revision + 1, admission] }),
  ];
  if (admission === 'quarantine') {
    // A phone holding a lease on one of its files loses it now; the file returns to the queue once the batch commits (below).
    statements.push(db.prepare("UPDATE extraction_leases SET state = 'cancelled', closed_at = ? WHERE source_id = ? AND state = 'leased'").bind(now, id));
    // Quarantine invalidates undispatched derived work for the source; nothing is deleted.
    statements.push(db.prepare("UPDATE jobs SET state = 'cancelled', error_code = 'quarantined' WHERE source_id = ? AND kind <> 'parse' AND state = 'pending'").bind(id));
    // Their attachments return to stored so release can queue them again under a new key.
    statements.push(
      db.prepare("UPDATE attachment_processing SET state = 'stored', job_id = NULL, revision = revision + 1 WHERE source_id = ? AND state IN ('queued', 'extracting') AND job_id IN (SELECT id FROM jobs WHERE state = 'cancelled')").bind(id),
    );
  }
  // Released sources get triage and extraction at their new revision; finished work is left as it is.
  if (admission === 'normal') {
    statements.push(...triageJobStatements(db, id, now, 'revision = ?', [revision + 1]));
    statements.push(...extractionJobStatements(db, id, now, 'r.revision = ?', [revision + 1]));
  }
  const results = await db.batch(statements);
  if (results[0]?.meta.changes !== 1) throw stale(revision + 1);
  if (admission === 'quarantine') await revertOrphans(db, now);
  const updated = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ?`).bind(id).first<RegistryRow>();
  return sourceProjection(updated ?? next);
}

// POST /sources/{id}/quarantine
export const quarantineSource = (env: Env, now: number, body: ReturnType<typeof quarantineBody>, id: string) => setAdmission(env, now, id, body.expectedRevision, 'quarantine');
// POST /sources/{id}/release — release never lowers the privacy floor; it enqueues triage at the new revision.
export const releaseSource = (env: Env, now: number, body: ReturnType<typeof releaseBody>, id: string) => setAdmission(env, now, id, body.expectedRevision, 'normal');
