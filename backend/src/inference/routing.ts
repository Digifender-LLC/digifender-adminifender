import type { CloudSettings, InferenceMode } from './settings';

export type Privacy = 'hybrid' | 'local_only' | 'no_ai' | 'restricted_metadata';
export type TaskKind = 'background' | 'interactive';

/** Baseline local readiness (baseline.md): battery >= 30 %, not severe thermal, model ready, one task at a time. */
export const MIN_BATTERY_PERCENT = 30;
/** A readiness report older than this no longer proves the phone can take work. */
export const READINESS_TTL_MS = 2 * 60 * 1000;
export const THERMAL_STATES = ['none', 'light', 'moderate', 'severe', 'critical', 'emergency', 'shutdown'] as const;
export type Thermal = (typeof THERMAL_STATES)[number];

export interface Readiness {
  reportedAt: number;
  foreground: boolean;
  charging: boolean;
  batteryPercent: number;
  thermal: Thermal;
  modelId: string | null;
}

/**
 * Whether a device report admits local work now: a foreground session for interactive tasks, or a charging
 * background worker for background jobs. OS-reported values only; model claims never count.
 */
export function localReady(report: Readiness | null, task: TaskKind, now: number): boolean {
  if (!report || now - report.reportedAt > READINESS_TTL_MS || report.reportedAt > now + 60_000) return false;
  if (!report.modelId || report.batteryPercent < MIN_BATTERY_PERCENT) return false;
  if (THERMAL_STATES.indexOf(report.thermal) >= THERMAL_STATES.indexOf('severe')) return false;
  return task === 'interactive' ? report.foreground : report.charging;
}

export type CloudGate = { ok: true } | { ok: false; reason: 'cloud_unavailable' | 'model_rate_unknown' | 'account_headroom_unknown' };

/**
 * Static preconditions for cloud dispatch (lld.md#inference): a bound model, its current rate, and either an account
 * whose other writers are known or an explicit paid ceiling (tdd.md#cost-model). With unknown headroom the shared
 * included allocation is not counted on, so every reservation is charged to the paid ceiling instead.
 */
export function cloudGate(cloud: CloudSettings, bound: boolean): CloudGate {
  if (!bound) return { ok: false, reason: 'cloud_unavailable' };
  if (!cloud.modelId || cloud.neuronsPerMillionInputTokens === null || cloud.neuronsPerMillionOutputTokens === null) return { ok: false, reason: 'model_rate_unknown' };
  const paidCeiling = cloud.paidMonthlyCeilingUsd > 0 && cloud.usdPerThousandNeurons !== null;
  if (cloud.account === 'unattested' && !paidCeiling) return { ok: false, reason: 'account_headroom_unknown' };
  return { ok: true };
}

export type RouteDecision =
  | { route: 'blocked'; reason: 'no_ai' | 'restricted' | 'quarantined' | 'deleted' }
  | { route: 'local'; reason: 'local_ready' }
  | { route: 'cloud'; reason: 'cloud_allowed' }
  | { route: 'queued_local'; reason: QueueReason };

/** Why work waits for a phone; budget reasons come from the reservation step. */
export type QueueReason = 'local_only' | 'cloud_unavailable' | 'model_rate_unknown' | 'account_headroom_unknown' | 'budget_exhausted';

export interface RouteInput {
  mode: InferenceMode;
  sources: { privacy: Privacy; admission: 'normal' | 'quarantine'; state: string }[];
  task: TaskKind;
  localReady: boolean;
  cloud: CloudGate;
}

/**
 * Deterministic routing order (lld.md#inference): no-AI is not allowed; quarantine and deletion block; local-only
 * waits for local; otherwise a ready local session, else cloud when its gate allows, else queue for local with
 * the reason exposed. The most restrictive source and the owner's global mode both apply.
 */
export function route(input: RouteInput): RouteDecision {
  if (input.sources.some((s) => s.state !== 'ready')) return { route: 'blocked', reason: 'deleted' };
  if (input.sources.some((s) => s.admission !== 'normal')) return { route: 'blocked', reason: 'quarantined' };
  if (input.mode === 'no_ai' || input.sources.some((s) => s.privacy === 'no_ai')) return { route: 'blocked', reason: 'no_ai' };
  // Only validated sanitized projections of restricted reports are model-eligible; raw content never is.
  if (input.sources.some((s) => s.privacy === 'restricted_metadata')) return { route: 'blocked', reason: 'restricted' };
  if (input.localReady) return { route: 'local', reason: 'local_ready' };
  if (input.mode === 'local_only' || input.sources.some((s) => s.privacy === 'local_only')) return { route: 'queued_local', reason: 'local_only' };
  if (input.cloud.ok) return { route: 'cloud', reason: 'cloud_allowed' };
  return { route: 'queued_local', reason: input.cloud.reason };
}
