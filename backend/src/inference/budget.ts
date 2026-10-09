import { assertion, isAssertionFailure } from '../db/assert';
import type { CloudSettings, InferenceSettings } from './settings';

export type UsageKind = 'automatic' | 'interactive';

/** An undispatched reservation older than this is released by the sweeper. */
export const RESERVATION_TTL_MS = 10 * 60 * 1000;
/** Baseline concurrency: one cloud request at a time. */
export const CLOUD_CONCURRENCY = 1;

export interface ReservedPeriod {
  id: string;
  requests: number;
  neurons: number;
}

export interface Reservation {
  periods: ReservedPeriod[];
  /** Worst-case neurons for the estimated maximum input and output tokens. */
  neurons: number;
  inputTokens: number;
  outputTokens: number;
  expiresAt: number;
}

interface Period {
  id: string;
  startsAt: number;
  endsAt: number;
  limit: { requests?: number; neurons?: number };
}

const day = (now: number) => new Date(now).toISOString().slice(0, 10);
const month = (now: number) => new Date(now).toISOString().slice(0, 7);
function dayBounds(now: number): [number, number] {
  const start = Date.UTC(new Date(now).getUTCFullYear(), new Date(now).getUTCMonth(), new Date(now).getUTCDate());
  return [start, start + 86_400_000];
}
function monthBounds(now: number): [number, number] {
  const d = new Date(now);
  return [Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1), Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)];
}

/** Worst-case neurons from the owner-entered model rate; rounded up so estimates never undercount. */
export function estimateNeurons(cloud: CloudSettings, inputTokens: number, outputTokens: number): number {
  const input = cloud.neuronsPerMillionInputTokens ?? Number.POSITIVE_INFINITY;
  const output = cloud.neuronsPerMillionOutputTokens ?? Number.POSITIVE_INFINITY;
  return Math.ceil((inputTokens * input + outputTokens * output) / 1_000_000);
}

/** Neurons the owner's paid monthly ceiling buys; zero (the default) means no paid inference. */
export function paidNeuronLimit(cloud: CloudSettings): number {
  if (cloud.paidMonthlyCeilingUsd <= 0 || cloud.usdPerThousandNeurons === null) return 0;
  return Math.floor((cloud.paidMonthlyCeilingUsd / cloud.usdPerThousandNeurons) * 1000);
}

function requestPeriod(settings: InferenceSettings, kind: UsageKind, now: number): Period {
  if (kind === 'automatic') {
    const [startsAt, endsAt] = dayBounds(now);
    return { id: `requests:${day(now)}:automatic`, startsAt, endsAt, limit: { requests: settings.limits.automaticPerDay } };
  }
  const [startsAt, endsAt] = monthBounds(now);
  return { id: `requests:${month(now)}:interactive`, startsAt, endsAt, limit: { requests: settings.limits.interactivePerMonth } };
}

/**
 * Included allocation first, then the paid monthly ceiling: whichever admits the whole worst case. The included
 * allocation is shared across the account, so it is used only when headroom is attested.
 */
function neuronPlans(settings: InferenceSettings, now: number): Period[] {
  const [dayStart, dayEnd] = dayBounds(now);
  const [monthStart, monthEnd] = monthBounds(now);
  const plans: Period[] = [];
  if (settings.cloud.includedNeuronsPerDay > 0 && settings.cloud.account !== 'unattested') plans.push({ id: `neurons:${day(now)}:included`, startsAt: dayStart, endsAt: dayEnd, limit: { neurons: settings.cloud.includedNeuronsPerDay } });
  const paid = paidNeuronLimit(settings.cloud);
  if (paid > 0) plans.push({ id: `neurons:${month(now)}:paid`, startsAt: monthStart, endsAt: monthEnd, limit: { neurons: paid } });
  return plans;
}

const ZERO = '{"requests":0,"neurons":0}';
const counter = (column: 'reserved_json' | 'settled_json', metric: 'requests' | 'neurons') => `COALESCE(json_extract(${column}, '$.${metric}'), 0)`;
const exposure = (metric: 'requests' | 'neurons') => `${counter('reserved_json', metric)} + ${counter('settled_json', metric)}`;

function adjust(db: D1Database, column: 'reserved_json' | 'settled_json', period: string, requests: number, neurons: number): D1PreparedStatement {
  return db
    .prepare(`UPDATE budget_periods SET ${column} = json_set(${column}, '$.requests', ${counter(column, 'requests')} + ?, '$.neurons', ${counter(column, 'neurons')} + ?) WHERE id = ?`)
    .bind(requests, neurons, period);
}

export type ReserveOutcome = { ok: true; reservation: Reservation } | { ok: false; reason: 'budget_exhausted' | 'cloud_busy' };

/**
 * Reserves one request and the worst-case neurons in one control transaction (lld.md#inference). Guarded
 * counters and the concurrency check are batch assertions, so concurrent reservations cannot overspend: the
 * loser's batch rolls back. `attach` adds the caller's dependent writes (the attempt row) to the same batch.
 */
export async function reserve(
  db: D1Database,
  settings: InferenceSettings,
  kind: UsageKind,
  tokens: { input: number; output: number },
  now: number,
  attach: (reservation: Reservation) => D1PreparedStatement[],
): Promise<ReserveOutcome> {
  const neurons = estimateNeurons(settings.cloud, tokens.input, tokens.output);
  const requests = requestPeriod(settings, kind, now);
  for (const plan of neuronPlans(settings, now)) {
    const reservation: Reservation = {
      periods: [
        { id: requests.id, requests: 1, neurons: 0 },
        { id: plan.id, requests: 0, neurons },
      ],
      neurons,
      inputTokens: tokens.input,
      outputTokens: tokens.output,
      expiresAt: now + RESERVATION_TTL_MS,
    };
    const periods = [requests, plan];
    const statements = [
      ...periods.map((p) =>
        db.prepare(`INSERT INTO budget_periods(id, starts_at, ends_at, limit_json, reserved_json, settled_json) VALUES (?, ?, ?, ?, '${ZERO}', '${ZERO}') ON CONFLICT(id) DO UPDATE SET limit_json = excluded.limit_json`)
          .bind(p.id, p.startsAt, p.endsAt, JSON.stringify(p.limit)),
      ),
      assertion(db, "(SELECT COUNT(*) FROM inference_attempts WHERE route = 'cloud' AND state IN ('reserved', 'dispatched')) < ?", CLOUD_CONCURRENCY),
      ...reservation.periods.map((r) =>
        assertion(
          db,
          `EXISTS (SELECT 1 FROM budget_periods WHERE id = ?
             AND ${exposure('requests')} + ? <= COALESCE(json_extract(limit_json, '$.requests'), 9e18)
             AND ${exposure('neurons')} + ? <= COALESCE(json_extract(limit_json, '$.neurons'), 9e18))`,
          r.id,
          r.requests,
          r.neurons,
        ),
      ),
      ...reservation.periods.map((r) => adjust(db, 'reserved_json', r.id, r.requests, r.neurons)),
      ...attach(reservation),
    ];
    try {
      await db.batch(statements);
      return { ok: true, reservation };
    } catch (error) {
      if (!isAssertionFailure(error)) throw error;
      const busy = await db.prepare("SELECT COUNT(*) AS n FROM inference_attempts WHERE route = 'cloud' AND state IN ('reserved', 'dispatched')").first<{ n: number }>();
      if ((busy?.n ?? 0) >= CLOUD_CONCURRENCY) return { ok: false, reason: 'cloud_busy' };
    }
  }
  return { ok: false, reason: 'budget_exhausted' };
}

/** Moves a reservation to settled usage: the request and the measured neurons (worst case when unknown). */
export function settleStatements(db: D1Database, reservation: Reservation, actualNeurons: number | null): D1PreparedStatement[] {
  return reservation.periods.flatMap((r) => [
    adjust(db, 'reserved_json', r.id, -r.requests, -r.neurons),
    adjust(db, 'settled_json', r.id, r.requests, r.neurons === 0 ? 0 : (actualNeurons ?? r.neurons)),
  ]);
}

/** Releases a reservation that was never dispatched; nothing was spent. */
export function releaseStatements(db: D1Database, reservation: Reservation): D1PreparedStatement[] {
  return reservation.periods.map((r) => adjust(db, 'reserved_json', r.id, -r.requests, -r.neurons));
}

/** Releases expired undispatched cloud reservations. Dispatched attempts keep their exposure. */
export async function releaseExpiredReservations(db: D1Database, now: number): Promise<{ released: number }> {
  const { results } = await db
    .prepare("SELECT id, reservation_json FROM inference_attempts WHERE route = 'cloud' AND state = 'reserved' AND dispatched_at IS NULL AND created_at <= ? LIMIT 50")
    .bind(now - RESERVATION_TTL_MS)
    .all<{ id: string; reservation_json: string }>();
  let released = 0;
  for (const row of results) {
    try {
      await db.batch([
        assertion(db, "EXISTS (SELECT 1 FROM inference_attempts WHERE id = ? AND state = 'reserved' AND dispatched_at IS NULL)", row.id),
        db.prepare("UPDATE inference_attempts SET state = 'released', error_code = 'reservation_expired', completed_at = ? WHERE id = ?").bind(now, row.id),
        ...releaseStatements(db, JSON.parse(row.reservation_json) as Reservation),
      ]);
      released += 1;
    } catch (error) {
      if (!isAssertionFailure(error)) throw error;
    }
  }
  return { released };
}

interface PeriodRow {
  id: string;
  starts_at: number;
  ends_at: number;
  limit_json: string;
  reserved_json: string;
  settled_json: string;
}

/** Current application counters. They do not control other applications' spending on a shared account. */
export async function budgetView(db: D1Database, now: number) {
  const { results } = await db.prepare('SELECT id, starts_at, ends_at, limit_json, reserved_json, settled_json FROM budget_periods WHERE starts_at <= ? AND ends_at > ? ORDER BY id').bind(now, now).all<PeriodRow>();
  return results.map((p) => ({
    id: p.id,
    startsAt: new Date(p.starts_at).toISOString(),
    endsAt: new Date(p.ends_at).toISOString(),
    limit: JSON.parse(p.limit_json) as Record<string, number>,
    reserved: JSON.parse(p.reserved_json) as Record<string, number>,
    settled: JSON.parse(p.settled_json) as Record<string, number>,
  }));
}
