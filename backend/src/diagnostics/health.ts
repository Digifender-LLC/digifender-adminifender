import { authenticate } from '../auth/sessions';
import { resolvePartition } from '../archive/partitions';
import type { Env } from '../env';
import { READINESS_TTL_MS } from '../inference/routing';
import { pushSender } from '../notifications/push';

/**
 * Private operational projection (lld.md#health, FR-032). Each component reports separately. Unknown values
 * are null and `unobserved` or `unavailable`, never zero or `ok`; stale telemetry says `stale`. Only counts,
 * ages, opaque IDs and fixed codes leave this view, never source text, subjects, addresses or keys.
 */

export type Status = 'ok' | 'warning' | 'stale' | 'unavailable' | 'unobserved';

const MIN_MS = 60 * 1000;
const DAY_MS = 24 * 60 * MIN_MS;
/** Attention targets from lld.md#health. */
export const CAPTURE_BACKLOG_MS = 15 * MIN_MS;
export const CLOUD_BACKLOG_MS = 15 * MIN_MS;
export const DEVICE_WAIT_MS = DAY_MS;
export const OUTBOX_BACKLOG_MS = 5 * MIN_MS;
export const SCHEDULER_STALE_MS = 5 * MIN_MS;
export const PARTITION_WARNING_BYTES = 7 * 1024 ** 3;
export const PARTITION_LIMIT_BYTES = 10 * 1024 ** 3;
export const SCHEDULED_PASS = 'scheduled_pass';

const iso = (ms: number | null | undefined) => (ms == null ? null : new Date(ms).toISOString());
const ageSeconds = (since: number | null | undefined, now: number) => (since == null ? null : Math.max(0, Math.floor((now - since) / 1000)));

/** Records a successful observation, such as the end of a scheduled pass. */
export async function observe(db: D1Database, name: string, now: number): Promise<void> {
  await db.prepare('INSERT INTO observations(name, observed_at) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET observed_at = MAX(observed_at, excluded.observed_at)').bind(name, now).run();
}

interface JobStats {
  open: number;
  oldestAgeSeconds: number | null;
  dead: number;
  failedLastDay: number;
}

/** Open work that is due now; a deferred reminder counts from its due time, other work from its creation. */
async function jobStats(db: D1Database, kind: string, now: number): Promise<JobStats> {
  const row = await db
    .prepare(
      `SELECT SUM(CASE WHEN state IN ('pending', 'leased', 'running') AND next_at <= ?1 THEN 1 ELSE 0 END) AS open,
              MIN(CASE WHEN state IN ('pending', 'leased', 'running') AND next_at <= ?1 THEN (CASE WHEN kind = 'notify' THEN next_at ELSE created_at END) END) AS oldest,
              SUM(CASE WHEN state = 'dead' THEN 1 ELSE 0 END) AS dead,
              SUM(CASE WHEN state = 'failed' AND created_at >= ?2 THEN 1 ELSE 0 END) AS failed
       FROM jobs WHERE kind = ?3`,
    )
    .bind(now, now - DAY_MS, kind)
    .first<{ open: number | null; oldest: number | null; dead: number | null; failed: number | null }>();
  return { open: row?.open ?? 0, oldestAgeSeconds: ageSeconds(row?.oldest, now), dead: row?.dead ?? 0, failedLastDay: row?.failed ?? 0 };
}

const backlogStatus = (stats: JobStats, limitMs: number): Status => (stats.dead > 0 || (stats.oldestAgeSeconds !== null && stats.oldestAgeSeconds * 1000 > limitMs) ? 'warning' : 'ok');

async function databaseBytes(db: D1Database | null): Promise<number | null> {
  if (!db) return null;
  try {
    const size = (await db.prepare('SELECT 1').run()).meta.size_after;
    return typeof size === 'number' ? size : null;
  } catch {
    return null;
  }
}

export interface Warning {
  component: string;
  code: string;
}

export async function privateHealth(env: Env, now: number) {
  const db = env.CONTROL_DB;
  const warnings: Warning[] = [];
  const warn = (component: string, code: string) => warnings.push({ component, code });

  const scheduled = await db.prepare('SELECT observed_at FROM observations WHERE name = ?').bind(SCHEDULED_PASS).first<{ observed_at: number }>();
  const schedulerStatus: Status = !scheduled ? 'unobserved' : now - scheduled.observed_at > SCHEDULER_STALE_MS ? 'stale' : 'ok';
  if (schedulerStatus !== 'ok') warn('scheduler', schedulerStatus === 'stale' ? 'scheduler_stale' : 'scheduler_unobserved');

  // Capture: last receipt and staged mail still waiting to parse. Quarantine is reported, not counted as backlog.
  const capture = await db
    .prepare(
      `SELECT MAX(received_at) AS last_receipt,
              SUM(CASE WHEN state IN ('staged', 'pending_index') AND admission = 'normal' THEN 1 ELSE 0 END) AS waiting,
              MIN(CASE WHEN state IN ('staged', 'pending_index') AND admission = 'normal' THEN received_at END) AS oldest_waiting,
              SUM(CASE WHEN state = 'parse_failed' THEN 1 ELSE 0 END) AS parse_failed,
              SUM(CASE WHEN admission = 'quarantine' AND state NOT IN ('deleting', 'deleted') THEN 1 ELSE 0 END) AS quarantined
       FROM source_registry WHERE kind = 'email'`,
    )
    .first<{ last_receipt: number | null; waiting: number | null; oldest_waiting: number | null; parse_failed: number | null; quarantined: number | null }>();
  const waitingAge = ageSeconds(capture?.oldest_waiting, now);
  const captureStatus: Status = capture?.last_receipt == null ? 'unobserved' : waitingAge !== null && waitingAge * 1000 > CAPTURE_BACKLOG_MS ? 'warning' : 'ok';
  if (captureStatus === 'warning') warn('capture', 'capture_backlog');

  const parse = await jobStats(db, 'parse', now);
  const parsingStatus: Status = (capture?.parse_failed ?? 0) > 0 ? 'warning' : backlogStatus(parse, CAPTURE_BACKLOG_MS);
  if (parsingStatus === 'warning') warn('parsing', (capture?.parse_failed ?? 0) > 0 ? 'parse_failed' : 'parse_backlog');

  const triage = await jobStats(db, 'triage', now);
  if (backlogStatus(triage, CAPTURE_BACKLOG_MS) === 'warning') warn('triage', 'triage_backlog');

  const extract = await jobStats(db, 'extract', now);
  const attachments = await db
    .prepare("SELECT SUM(CASE WHEN state = 'deferred' THEN 1 ELSE 0 END) AS deferred, SUM(CASE WHEN state = 'failed' THEN 1 ELSE 0 END) AS failed, SUM(CASE WHEN state = 'unsupported' THEN 1 ELSE 0 END) AS unsupported FROM attachment_processing")
    .first<{ deferred: number | null; failed: number | null; unsupported: number | null }>();
  const extractionStatus = backlogStatus(extract, CAPTURE_BACKLOG_MS);
  if (extractionStatus === 'warning') warn('extraction', 'extraction_backlog');

  // Inference: cloud backlog, phone work waiting for a device, and uncertain attempts awaiting reconciliation.
  const infer = await jobStats(db, 'infer', now);
  const chat = await jobStats(db, 'chat', now);
  const attempts = await db
    .prepare(
      `SELECT SUM(CASE WHEN state = 'queued' THEN 1 ELSE 0 END) AS queued, MIN(CASE WHEN state = 'queued' THEN created_at END) AS oldest_queued,
              SUM(CASE WHEN state = 'uncertain' THEN 1 ELSE 0 END) AS uncertain
       FROM inference_attempts`,
    )
    .first<{ queued: number | null; oldest_queued: number | null; uncertain: number | null }>();
  const allowance = await db.prepare('SELECT MAX(observed_at) AS at FROM budget_periods').first<{ at: number | null }>();
  const deviceWait = ageSeconds(attempts?.oldest_queued, now);
  let inferenceStatus: Status = 'ok';
  if (backlogStatus(infer, CLOUD_BACKLOG_MS) === 'warning' || backlogStatus(chat, CLOUD_BACKLOG_MS) === 'warning') {
    inferenceStatus = 'warning';
    warn('inference', 'inference_backlog');
  }
  if (deviceWait !== null && deviceWait * 1000 > DEVICE_WAIT_MS) {
    inferenceStatus = 'warning';
    warn('inference', 'device_work_waiting');
  }
  if ((attempts?.uncertain ?? 0) > 0) {
    inferenceStatus = 'warning';
    warn('inference', 'inference_uncertain');
  }

  // Devices: readiness telemetry is labeled stale once older than the routing window.
  const { results: deviceRows } = await db
    .prepare(
      `SELECT d.id, d.last_sync_at, d.fcm_token_cipher IS NOT NULL AS has_push, d.push_permission, d.push_invalid_at, r.reported_at, r.model_id
       FROM devices d LEFT JOIN device_readiness r ON r.device_id = d.id WHERE d.revoked_at IS NULL ORDER BY d.id`,
    )
    .all<{ id: string; last_sync_at: number | null; has_push: number; push_permission: string | null; push_invalid_at: number | null; reported_at: number | null; model_id: string | null }>();
  const devices = deviceRows.map((d) => ({
    deviceId: d.id,
    readiness: d.reported_at === null ? 'unobserved' : now - d.reported_at > READINESS_TTL_MS ? 'stale' : 'ok',
    readinessReportedAt: iso(d.reported_at),
    modelLoaded: d.reported_at === null ? null : d.model_id !== null,
    lastSyncAt: iso(d.last_sync_at),
    push: d.has_push !== 1 ? 'unregistered' : d.push_invalid_at !== null ? 'invalid' : (d.push_permission ?? 'unregistered'),
  }));

  // Sync: the change feed position and when a device last caught up with it.
  const latest = await db.prepare('SELECT seq, created_at FROM changes ORDER BY seq DESC LIMIT 1').first<{ seq: number; created_at: number }>();
  const floor = await db.prepare("SELECT seq FROM change_floor WHERE id = 'floor'").first<{ seq: number }>();
  const lastSync = Math.max(...deviceRows.map((d) => d.last_sync_at ?? -1), -1);

  // Notifications: whether a push can currently be delivered, and what recent sends reported.
  const adapter = pushSender(env) !== null;
  const deliverable = devices.filter((d) => d.push === 'granted').length;
  const recent = (
    await db
      .prepare('SELECT state, reason, COUNT(*) AS n FROM notification_events WHERE created_at >= ? GROUP BY state, reason ORDER BY state, reason')
      .bind(now - DAY_MS)
      .all<{ state: string; reason: string | null; n: number }>()
  ).results;
  const lastSubmitted = await db.prepare("SELECT MAX(sent_at) AS at FROM notification_deliveries WHERE state = 'sent'").first<{ at: number | null }>();
  let notificationStatus: Status = 'ok';
  if (!adapter || deliverable === 0) {
    notificationStatus = 'unavailable';
    warn('notifications', !adapter ? 'push_unavailable' : 'no_push_device');
  } else if (recent.some((r) => r.state === 'failed' || r.state === 'uncertain')) {
    notificationStatus = 'warning';
    warn('notifications', 'push_errors');
  }

  const outbox = await db
    .prepare("SELECT COUNT(*) AS n, MIN(next_at) AS oldest FROM outbox WHERE state = 'pending' AND next_at <= ?")
    .bind(now)
    .first<{ n: number; oldest: number | null }>();
  const outboxAge = ageSeconds(outbox?.oldest, now);
  if (outboxAge !== null && outboxAge * 1000 > OUTBOX_BACKLOG_MS) warn('outbox', 'outbox_backlog');

  const { results: partitionRows } = await db.prepare('SELECT id, binding_name, state FROM archive_partitions ORDER BY id').all<{ id: number; binding_name: string; state: string }>();
  const partitions = [];
  for (const p of [{ id: 0, binding_name: 'CONTROL_DB', state: 'control' }, ...partitionRows]) {
    const bytes = await databaseBytes(p.binding_name === 'CONTROL_DB' ? db : resolvePartition(env, p.binding_name));
    if (bytes !== null && bytes >= PARTITION_WARNING_BYTES) warn('storage', 'partition_capacity');
    partitions.push({ id: p.id, role: p.state === 'control' ? 'control' : 'archive', state: p.state === 'control' ? null : p.state, sizeBytes: bytes, headroomBytes: bytes === null ? null : PARTITION_LIMIT_BYTES - bytes });
  }

  const overall = await db
    .prepare("SELECT MIN(CASE WHEN kind = 'notify' THEN next_at ELSE created_at END) AS oldest, SUM(CASE WHEN state = 'dead' THEN 1 ELSE 0 END) AS dead FROM jobs WHERE (state IN ('pending', 'leased', 'running') AND next_at <= ?) OR state = 'dead'")
    .bind(now)
    .first<{ oldest: number | null; dead: number | null }>();

  return {
    generatedAt: iso(now),
    scheduler: { status: schedulerStatus, lastPassAt: iso(scheduled?.observed_at) },
    capture: {
      status: captureStatus,
      lastReceiptAt: iso(capture?.last_receipt),
      waiting: capture?.waiting ?? 0,
      oldestWaitingAgeSeconds: waitingAge,
      quarantined: capture?.quarantined ?? 0,
    },
    parsing: { status: parsingStatus, ...parse, parseFailed: capture?.parse_failed ?? 0 },
    triage: { status: backlogStatus(triage, CAPTURE_BACKLOG_MS), ...triage },
    extraction: { status: extractionStatus, ...extract, deferredForDevice: attachments?.deferred ?? 0, failed: attachments?.failed ?? 0, unsupported: attachments?.unsupported ?? 0 },
    inference: {
      status: inferenceStatus,
      cloudBound: env.AI !== undefined,
      triage: infer,
      chat,
      waitingForDevice: attempts?.queued ?? 0,
      oldestDeviceWaitAgeSeconds: deviceWait,
      uncertainAttempts: attempts?.uncertain ?? 0,
      // Provider headroom is never read back yet, so allowance freshness stays unobserved.
      allowance: { status: allowance?.at == null ? 'unobserved' : 'ok', observedAt: iso(allowance?.at) },
    },
    sync: {
      status: (lastSync < 0 ? 'unobserved' : 'ok') as Status,
      latestSeq: latest?.seq ?? 0,
      latestChangeAt: iso(latest?.created_at),
      floorSeq: floor?.seq ?? 0,
      lastDeviceSyncAt: lastSync < 0 ? null : iso(lastSync),
    },
    notifications: {
      status: notificationStatus,
      adapterConfigured: adapter,
      deliverableDevices: deliverable,
      lastSubmittedAt: iso(lastSubmitted?.at),
      lastDay: recent.map((r) => ({ state: r.state, reason: r.reason, count: r.n })),
    },
    outbox: { status: (outboxAge !== null && outboxAge * 1000 > OUTBOX_BACKLOG_MS ? 'warning' : 'ok') as Status, pending: outbox?.n ?? 0, oldestAgeSeconds: outboxAge },
    storage: { partitions },
    backlog: { oldestAgeSeconds: ageSeconds(overall?.oldest, now), deadJobs: overall?.dead ?? 0 },
    devices,
    warnings,
  };
}

// GET /health/private
export async function getPrivateHealth(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  return privateHealth(env, now);
}
