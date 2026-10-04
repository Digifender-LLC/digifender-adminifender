import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError, notFound } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { type Effect, type Predicate, parseEffects, parsePredicate } from './rules';

interface RuleRow {
  id: string;
  revision: number;
  origin: 'owner' | 'learned';
  enabled: number;
  predicate_json: string;
  effect_json: string;
  created_at: number;
}

const stale = (current: number) => new HttpError(409, 'stale_revision', 'This item changed. Reload and try again.', { currentRevision: current });
const predicate: Validator<Predicate> = (value, path) => parsePredicate(value, path);
const effects: Validator<Effect[]> = (value, path) => parseEffects(value, path);
const expectedRevision = v.integer({ min: 1, max: Number.MAX_SAFE_INTEGER });

function ruleView(row: RuleRow) {
  return {
    id: row.id,
    revision: row.revision,
    origin: row.origin,
    enabled: row.enabled === 1,
    predicate: JSON.parse(row.predicate_json) as Predicate,
    effects: JSON.parse(row.effect_json) as Effect[],
    createdAt: new Date(row.created_at).toISOString(),
  };
}

/** Assigned services must exist; rules never create catalog entries. */
async function checkServices(env: Env, list: Effect[]): Promise<void> {
  for (const [i, effect] of list.entries()) {
    if (!('assign_service' in effect)) continue;
    const found = await env.CONTROL_DB.prepare('SELECT 1 AS hit FROM service_catalog WHERE id = ?').bind(effect.assign_service).first();
    if (!found) throw new HttpError(422, 'unknown_service', 'That service is not in the catalog.', { path: `$.effects[${i}].assign_service` });
  }
}

// GET /rules
export async function listRules(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const { results } = await env.CONTROL_DB.prepare('SELECT * FROM rules ORDER BY created_at, id').all<RuleRow>();
  return { rules: results.map(ruleView) };
}

export const ruleBody = v.object({ predicate, effects, enabled: v.optional(v.boolean()) });

// POST /rules — owner rules apply to sources triaged after the change; archived text and past results are untouched.
export async function createRule(env: Env, now: number, body: ReturnType<typeof ruleBody>) {
  await checkServices(env, body.effects);
  const row: RuleRow = {
    id: crypto.randomUUID(),
    revision: 1,
    origin: 'owner',
    enabled: body.enabled === false ? 0 : 1,
    predicate_json: JSON.stringify(body.predicate),
    effect_json: JSON.stringify(body.effects),
    created_at: now,
  };
  await env.CONTROL_DB.prepare('INSERT INTO rules(id, revision, origin, enabled, predicate_json, effect_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)')
    .bind(row.id, row.revision, row.origin, row.enabled, row.predicate_json, row.effect_json, row.created_at)
    .run();
  return ruleView(row);
}

export const rulePatch = v.object({ expectedRevision, enabled: v.optional(v.boolean()), predicate: v.optional(predicate), effects: v.optional(effects) });

// PATCH /rules/{id} — learned rules can only be enabled or disabled; their conditions come from evaluation.
export async function updateRule(env: Env, _now: number, body: ReturnType<typeof rulePatch>, id: string) {
  const current = await env.CONTROL_DB.prepare('SELECT * FROM rules WHERE id = ?').bind(id).first<RuleRow>();
  if (!current) throw notFound();
  if (current.revision !== body.expectedRevision) throw stale(current.revision);
  if (current.origin === 'learned' && (body.predicate || body.effects)) {
    throw new HttpError(422, 'learned_rule_read_only', 'A learned rule can only be turned on or off.');
  }
  if (body.effects) await checkServices(env, body.effects);
  const next: RuleRow = {
    ...current,
    revision: current.revision + 1,
    enabled: body.enabled === undefined ? current.enabled : body.enabled ? 1 : 0,
    predicate_json: body.predicate ? JSON.stringify(body.predicate) : current.predicate_json,
    effect_json: body.effects ? JSON.stringify(body.effects) : current.effect_json,
  };
  const result = await env.CONTROL_DB.prepare('UPDATE rules SET revision = ?, enabled = ?, predicate_json = ?, effect_json = ? WHERE id = ? AND revision = ?')
    .bind(next.revision, next.enabled, next.predicate_json, next.effect_json, id, current.revision)
    .run();
  if (result.meta.changes !== 1) throw stale(current.revision + 1);
  return ruleView(next);
}
