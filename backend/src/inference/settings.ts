import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { type Validator, v } from '../http/validate';

/** Baseline cloud throttle (baseline.md): the owner may lower these, never raise them. */
export const MAX_AUTOMATIC_PER_DAY = 100;
export const MAX_INTERACTIVE_PER_MONTH = 1000;

export type InferenceMode = 'auto' | 'local_only' | 'no_ai';
export type AccountAttestation = 'unattested' | 'dedicated' | 'coordinated';

export interface CloudSettings {
  /** Workers AI text model; cloud stays off until the owner names one with its current rates. */
  modelId: string | null;
  neuronsPerMillionInputTokens: number | null;
  neuronsPerMillionOutputTokens: number | null;
  /**
   * Whether this application's reservations see every writer on the account: `dedicated` (no other writer) or
   * `coordinated` (every writer reserves through the same counters). Anything else leaves headroom unknown.
   */
  account: AccountAttestation;
  /** Included daily allocation the owner read from the provider for this account. */
  includedNeuronsPerDay: number;
  /** Zero (the default) means no incremental paid inference. */
  paidMonthlyCeilingUsd: number;
  usdPerThousandNeurons: number | null;
}

export interface InferenceSettings {
  mode: InferenceMode;
  limits: { automaticPerDay: number; interactivePerMonth: number };
  cloud: CloudSettings;
}

export interface Settings {
  revision: number;
  inference: InferenceSettings;
}

export const DEFAULT_INFERENCE: InferenceSettings = {
  mode: 'auto',
  limits: { automaticPerDay: MAX_AUTOMATIC_PER_DAY, interactivePerMonth: MAX_INTERACTIVE_PER_MONTH },
  cloud: {
    modelId: null,
    neuronsPerMillionInputTokens: null,
    neuronsPerMillionOutputTokens: null,
    account: 'unattested',
    includedNeuronsPerDay: 0,
    paidMonthlyCeilingUsd: 0,
    usdPerThousandNeurons: null,
  },
};

const rate = v.nullable(v.number({ min: 0.000001, max: 10_000_000 }));
const inferenceShape: Validator<InferenceSettings> = v.object({
  mode: v.oneOf(['auto', 'local_only', 'no_ai'] as const),
  limits: v.object({
    automaticPerDay: v.integer({ min: 0, max: MAX_AUTOMATIC_PER_DAY }),
    interactivePerMonth: v.integer({ min: 0, max: MAX_INTERACTIVE_PER_MONTH }),
  }),
  cloud: v.object({
    modelId: v.nullable(v.string({ max: 100, pattern: /^@cf\/[a-z0-9._-]+\/[a-z0-9._-]+$/ })),
    neuronsPerMillionInputTokens: rate,
    neuronsPerMillionOutputTokens: rate,
    account: v.oneOf(['unattested', 'dedicated', 'coordinated'] as const),
    includedNeuronsPerDay: v.integer({ min: 0, max: 1_000_000_000 }),
    paidMonthlyCeilingUsd: v.number({ min: 0, max: 100_000 }),
    usdPerThousandNeurons: rate,
  }),
});

/** Current owner settings; revision 0 means defaults that were never saved. */
export async function loadSettings(db: D1Database): Promise<Settings> {
  const row = await db.prepare("SELECT revision, values_json FROM settings WHERE id = 'owner'").first<{ revision: number; values_json: string }>();
  if (!row) return { revision: 0, inference: DEFAULT_INFERENCE };
  const values = JSON.parse(row.values_json) as { inference?: InferenceSettings };
  return { revision: row.revision, inference: values.inference ?? DEFAULT_INFERENCE };
}

const view = (settings: Settings) => ({ revision: settings.revision, inference: settings.inference });

// GET /settings
export async function getSettings(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  return view(await loadSettings(env.CONTROL_DB));
}

export const settingsPatch = v.object({ expectedRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }), inference: v.optional(inferenceShape) });

/**
 * PATCH /settings — one revisioned object (lld.md#settings). A changed inference section raises the policy
 * revision, so device results computed under the old policy are rejected.
 */
export async function patchSettings(env: Env, now: number, body: ReturnType<typeof settingsPatch>) {
  const db = env.CONTROL_DB;
  const current = await loadSettings(db);
  if (current.revision !== body.expectedRevision) throw new HttpError(409, 'stale_revision', 'Settings changed. Reload and try again.', { currentRevision: current.revision });
  const next: Settings = { revision: current.revision + 1, inference: body.inference ?? current.inference };
  const values = JSON.stringify({ inference: next.inference });
  const result =
    current.revision === 0
      ? await db.prepare("INSERT INTO settings(id, revision, values_json, updated_at) SELECT 'owner', 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM settings WHERE id = 'owner')").bind(values, now).run()
      : await db.prepare("UPDATE settings SET revision = revision + 1, values_json = ?, updated_at = ? WHERE id = 'owner' AND revision = ?").bind(values, now, current.revision).run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'stale_revision', 'Settings changed. Reload and try again.');
  return view(next);
}
