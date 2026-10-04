/**
 * Validation of model triage output (lld.md#triage). Mirrors contracts/schema/v1/triage-output.schema.json and
 * adds the domain checks the schema cannot express: every evidence range lies inside a chunk the model was
 * given, and every service ID is in the catalog. Errors are paths and reasons only, never model text.
 */

export const CATEGORIES = ['operations', 'billing', 'security', 'personal', 'support', 'other'] as const;
export const IMPORTANCE = ['routine', 'important', 'urgent'] as const;
export const ACTION_KINDS = [
  'email.reply',
  'cloudflare.dns.upsert',
  'cloudflare.dns.delete',
  'cloudflare.worker.version.activate',
  'whumpo.release.apply',
  'whumpo.recovery.execute',
  'whumpo.support.respond',
] as const;

export interface TriageOutput {
  summary: string;
  category: (typeof CATEGORIES)[number];
  importance: (typeof IMPORTANCE)[number];
  uncertainty: string[];
  deadline: { text: string; instant?: string; zone?: string; ambiguous: boolean } | null;
  serviceIds: string[];
  evidence: { chunkId: string; start: number; end: number }[];
  suggestedActions: { kind: (typeof ACTION_KINDS)[number]; targetId: string; description: string }[];
}

export type Validation = { ok: true; value: TriageOutput } | { ok: false; errors: string[] };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;
const CHUNK_ID = /^[A-Za-z0-9:_.-]{1,200}$/;

const isObject = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const isString = (value: unknown, max: number): value is string => typeof value === 'string' && value.length >= 1 && value.length <= max;
const isInt = (value: unknown, max: number): value is number => typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= max;

function onlyKeys(value: Record<string, unknown>, allowed: string[], required: string[], path: string, errors: string[]): void {
  for (const key of Object.keys(value)) if (!allowed.includes(key)) errors.push(`${path}.${key}:unknown_field`);
  for (const key of required) if (!Object.hasOwn(value, key)) errors.push(`${path}.${key}:required`);
}

/** Model text to JSON. A single fenced block is tolerated; anything else must be the bare object. */
export function parseModelJson(text: string): { ok: true; value: unknown } | { ok: false; errors: string[] } {
  const fenced = /^\s*```(?:json)?\s*\n([\s\S]*?)\n\s*```\s*$/.exec(text);
  try {
    return { ok: true, value: JSON.parse(fenced ? (fenced[1] ?? '') : text) };
  } catch {
    return { ok: false, errors: ['$:not_json'] };
  }
}

/** A textual critical suggestion becomes urgent awaiting an explicit rule or owner confirmation. */
export function normalizeTriage(value: unknown): unknown {
  if (isObject(value) && typeof value.importance === 'string' && value.importance.toLowerCase() === 'critical') return { ...value, importance: 'urgent' };
  return value;
}

export function validateTriage(value: unknown, chunks: ReadonlyMap<string, number>, knownServiceIds: ReadonlySet<string>): Validation {
  const errors: string[] = [];
  if (!isObject(value)) return { ok: false, errors: ['$:expected_object'] };
  onlyKeys(value, ['summary', 'category', 'importance', 'uncertainty', 'deadline', 'serviceIds', 'evidence', 'suggestedActions'], ['summary', 'category', 'importance', 'uncertainty', 'deadline', 'serviceIds', 'evidence', 'suggestedActions'], '$', errors);
  if (!isString(value.summary, 2000)) errors.push('$.summary:invalid');
  if (!(CATEGORIES as readonly unknown[]).includes(value.category)) errors.push('$.category:unknown_value');
  if (!(IMPORTANCE as readonly unknown[]).includes(value.importance)) errors.push('$.importance:unknown_value');
  if (!Array.isArray(value.uncertainty) || value.uncertainty.length > 10 || !value.uncertainty.every((u) => isString(u, 300))) errors.push('$.uncertainty:invalid');

  const deadline = value.deadline;
  if (deadline !== null) {
    if (!isObject(deadline)) errors.push('$.deadline:invalid');
    else {
      onlyKeys(deadline, ['text', 'instant', 'zone', 'ambiguous'], ['text', 'ambiguous'], '$.deadline', errors);
      if (!isString(deadline.text, 300)) errors.push('$.deadline.text:invalid');
      if (typeof deadline.ambiguous !== 'boolean') errors.push('$.deadline.ambiguous:invalid');
      if (deadline.instant !== undefined && !(typeof deadline.instant === 'string' && TIMESTAMP.test(deadline.instant) && !Number.isNaN(Date.parse(deadline.instant)))) errors.push('$.deadline.instant:invalid');
      if (deadline.zone !== undefined && !isString(deadline.zone, 64)) errors.push('$.deadline.zone:invalid');
    }
  }

  if (!Array.isArray(value.serviceIds) || value.serviceIds.length > 20 || new Set(value.serviceIds).size !== value.serviceIds.length) errors.push('$.serviceIds:invalid');
  else value.serviceIds.forEach((id, i) => {
    if (typeof id !== 'string' || !UUID.test(id)) errors.push(`$.serviceIds[${i}]:invalid`);
    else if (!knownServiceIds.has(id)) errors.push(`$.serviceIds[${i}]:unknown_service`);
  });

  if (!Array.isArray(value.evidence) || value.evidence.length > 20) errors.push('$.evidence:invalid');
  else value.evidence.forEach((item, i) => {
    const path = `$.evidence[${i}]`;
    if (!isObject(item)) return errors.push(`${path}:invalid`);
    onlyKeys(item, ['chunkId', 'start', 'end'], ['chunkId', 'start', 'end'], path, errors);
    if (typeof item.chunkId !== 'string' || !CHUNK_ID.test(item.chunkId)) return errors.push(`${path}.chunkId:invalid`);
    const length = chunks.get(item.chunkId);
    if (length === undefined) return errors.push(`${path}.chunkId:not_in_context`);
    if (!isInt(item.start, 6000) || !isInt(item.end, 6000) || item.start >= item.end || item.end > length) errors.push(`${path}:outside_chunk`);
  });

  if (!Array.isArray(value.suggestedActions) || value.suggestedActions.length > 5) errors.push('$.suggestedActions:invalid');
  else value.suggestedActions.forEach((item, i) => {
    const path = `$.suggestedActions[${i}]`;
    if (!isObject(item)) return errors.push(`${path}:invalid`);
    onlyKeys(item, ['kind', 'targetId', 'description'], ['kind', 'targetId', 'description'], path, errors);
    if (!(ACTION_KINDS as readonly unknown[]).includes(item.kind)) errors.push(`${path}.kind:unknown_value`);
    if (!isString(item.targetId, 200)) errors.push(`${path}.targetId:invalid`);
    if (!isString(item.description, 500)) errors.push(`${path}.description:invalid`);
  });

  return errors.length === 0 ? { ok: true, value: value as unknown as TriageOutput } : { ok: false, errors: errors.slice(0, 20) };
}
