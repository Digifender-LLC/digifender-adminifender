import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { type Validator, v } from '../http/validate';
import { type NotificationSettings, DEFAULT_NOTIFICATIONS, notificationsShape } from '../notifications/settings';

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
  /** Revision of the whole settings object; PATCH compares `expectedRevision` with it. */
  revision: number;
  /**
   * Inference policy revision: raised only when the inference section changes, so a notification or other
   * unrelated edit does not reject phone results computed under the same inference policy.
   */
  policyRevision: number;
  inference: InferenceSettings;
  notifications: NotificationSettings;
}

/** SQL expression for the current inference policy revision, for batch guards. */
export const POLICY_REVISION_SQL = "COALESCE((SELECT COALESCE(json_extract(values_json, '$.policyRevision'), revision) FROM settings WHERE id = 'owner'), 0)";

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
  if (!row) return { revision: 0, policyRevision: 0, inference: DEFAULT_INFERENCE, notifications: DEFAULT_NOTIFICATIONS };
  const values = JSON.parse(row.values_json) as { policyRevision?: number; inference?: InferenceSettings; notifications?: NotificationSettings };
  return {
    revision: row.revision,
    policyRevision: values.policyRevision ?? row.revision,
    inference: values.inference ?? DEFAULT_INFERENCE,
    notifications: values.notifications ?? DEFAULT_NOTIFICATIONS,
  };
}

const view = (settings: Settings) => ({ revision: settings.revision, policyRevision: settings.policyRevision, inference: settings.inference, notifications: settings.notifications });

// GET /settings
export async function getSettings(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  return view(await loadSettings(env.CONTROL_DB));
}

export const settingsPatch = v.object({
  expectedRevision: v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER }),
  inference: v.optional(inferenceShape),
  notifications: v.optional(notificationsShape),
});

/**
 * PATCH /settings — one revisioned object (lld.md#settings). A changed inference section raises the inference
 * policy revision, so device results computed under the old policy are rejected; other sections do not.
 */
export async function patchSettings(env: Env, now: number, body: ReturnType<typeof settingsPatch>) {
  const db = env.CONTROL_DB;
  const current = await loadSettings(db);
  if (current.revision !== body.expectedRevision) throw new HttpError(409, 'stale_revision', 'Settings changed. Reload and try again.', { currentRevision: current.revision });
  const inference = body.inference ?? current.inference;
  const revision = current.revision + 1;
  const inferenceChanged = JSON.stringify(inference) !== JSON.stringify(current.inference);
  const next: Settings = { revision, policyRevision: inferenceChanged ? revision : current.policyRevision, inference, notifications: body.notifications ?? current.notifications };
  const values = JSON.stringify({ policyRevision: next.policyRevision, inference: next.inference, notifications: next.notifications });
  const result =
    current.revision === 0
      ? await db.prepare("INSERT INTO settings(id, revision, values_json, updated_at) SELECT 'owner', 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM settings WHERE id = 'owner')").bind(values, now).run()
      : await db.prepare("UPDATE settings SET revision = revision + 1, values_json = ?, updated_at = ? WHERE id = 'owner' AND revision = ?").bind(values, now, current.revision).run();
  if (result.meta.changes !== 1) throw new HttpError(409, 'stale_revision', 'Settings changed. Reload and try again.');
  return view(next);
}
