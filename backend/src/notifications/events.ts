import type { ConcernRow } from '../concerns/model';
import { localTime, inQuietHours } from './zone';
import type { EventKind } from './push';
import { loadSettings } from '../inference/settings';

/** Event lifetimes (lld.md#notifications); due reminders keep the critical lifetime. */
export const CRITICAL_TTL_MS = 24 * 60 * 60 * 1000;
export const DUE_TTL_MS = 24 * 60 * 60 * 1000;
export const INACTIVITY_TTL_MS = 6 * 60 * 60 * 1000;
const HOUR_MS = 60 * 60 * 1000;

export const NOTIFY_JOB = 'notify';

interface NewEvent {
  dedupeKey: string;
  kind: EventKind;
  concernId: string | null;
  concernRevision: number | null;
  signalId: string | null;
  dueRevision: number | null;
  notBefore: number;
  expiresAt: number;
}

/**
 * Event, dispatch job and outbox row in the caller's transaction. A repeated dedupe key (or, for critical
 * events, an already-notified signal) inserts nothing, and the job and outbox follow only a new event.
 */
export function eventStatements(db: D1Database, event: NewEvent, now: number, condition = '1', params: unknown[] = []): D1PreparedStatement[] {
  const id = crypto.randomUUID();
  const jobId = crypto.randomUUID();
  return [
    db
      .prepare(
        `INSERT OR IGNORE INTO notification_events(id, dedupe_key, concern_id, concern_revision, kind, state, not_before, expires_at, created_at, signal_id, due_revision, job_id)
         SELECT ?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ? WHERE ${condition}`,
      )
      .bind(id, event.dedupeKey, event.concernId, event.concernRevision, event.kind, event.notBefore, event.expiresAt, now, event.signalId, event.dueRevision, jobId, ...params),
    db
      .prepare(
        `INSERT INTO jobs(id, dedupe_key, kind, source_id, input_revision, state, next_at, input_json, created_at)
         SELECT ?, ?, ?, NULL, NULL, 'pending', ?, ?, ? WHERE EXISTS (SELECT 1 FROM notification_events WHERE id = ? AND job_id = ?)`,
      )
      .bind(jobId, `${NOTIFY_JOB}:${id}`, NOTIFY_JOB, event.notBefore, JSON.stringify({ eventId: id }), now, id, jobId),
    db
      .prepare("INSERT INTO outbox(id, dedupe_key, kind, reference_id, state, next_at) SELECT ?, ?, 'job', ?, 'pending', ? WHERE EXISTS (SELECT 1 FROM jobs WHERE id = ?)")
      .bind(crypto.randomUUID(), `job:${jobId}`, jobId, event.notBefore, jobId),
  ];
}

/**
 * Events implied by one concern revision: a push for a new rule-confirmed critical signal, and a due reminder
 * for a new due-date revision. Mark-read, acknowledge and snooze edits change neither, so they mint nothing.
 * AI-only importance is never `critical` (inference translates it to urgent), and an owner correction to
 * critical is the owner's own act, so neither pushes as critical.
 */
export function concernEventStatements(db: D1Database, before: ConcernRow | null, after: ConcernRow, now: number, condition: string, params: unknown[]): D1PreparedStatement[] {
  const statements: D1PreparedStatement[] = [];
  const live = after.status !== 'resolved' && after.status !== 'snoozed';
  const signal = after.critical_signal_id;
  if (live && after.importance === 'critical' && signal && signal !== before?.critical_signal_id && after.critical_basis?.startsWith('rule:')) {
    statements.push(
      ...eventStatements(
        db,
        { dedupeKey: `critical:${after.id}:${signal}`, kind: 'critical', concernId: after.id, concernRevision: after.revision, signalId: signal, dueRevision: null, notBefore: now, expiresAt: now + CRITICAL_TTL_MS },
        now,
        condition,
        params,
      ),
    );
  }
  if (after.status !== 'resolved' && after.due_at !== null && (before === null || after.due_revision !== before.due_revision)) {
    const notBefore = Math.max(after.due_at, now);
    statements.push(
      ...eventStatements(
        db,
        { dedupeKey: `due:${after.id}:${after.due_revision}`, kind: 'due', concernId: after.id, concernRevision: after.revision, signalId: null, dueRevision: after.due_revision, notBefore, expiresAt: notBefore + DUE_TTL_MS },
        now,
        condition,
        params,
      ),
    );
  }
  return statements;
}

/** A concern still backed by at least one ready, admitted source. */
export const HAS_LIVE_SOURCE = "EXISTS (SELECT 1 FROM concern_sources cs JOIN source_registry r ON r.id = cs.source_id WHERE cs.concern_id = c.id AND r.state = 'ready' AND r.admission = 'normal')";

/**
 * Unresolved work that justifies an inactivity reminder (FR-030): open items that are not purely routine or are
 * due, and waiting items only once due or overdue. Snoozed and resolved items never count.
 */
export async function eligibleBacklog(db: D1Database, now: number): Promise<number> {
  const row = await db
    .prepare(
      `SELECT COUNT(*) AS n FROM concerns c
       WHERE ((c.status = 'open' AND (c.importance <> 'routine' OR (c.due_at IS NOT NULL AND c.due_at <= ?)))
          OR (c.status = 'waiting' AND c.due_at IS NOT NULL AND c.due_at <= ?))
         AND ${HAS_LIVE_SOURCE}`,
    )
    .bind(now, now)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/**
 * Scheduled check for one inactivity reminder: after the configured time without meaningful review, with
 * eligible backlog, outside quiet hours, at most once per cooldown. App launch, sync and push receipt do not
 * count as review, so they cannot postpone or cancel it. The dedupe key is the review epoch (the last
 * meaningful review time) and local date.
 */
export async function scheduleInactivity(db: D1Database, now: number): Promise<{ created: boolean; reason: string }> {
  const { notifications: n } = await loadSettings(db);
  if (!n.channels.inactivity) return { created: false, reason: 'channel_disabled' };
  if (inQuietHours(now, n.zone, n.quietHours)) return { created: false, reason: 'quiet_hours' };
  const owner = await db.prepare("SELECT created_at FROM owner WHERE id = 'owner'").first<{ created_at: number }>();
  if (!owner) return { created: false, reason: 'no_owner' };
  const review = await db.prepare("SELECT last_meaningful_at FROM review_state WHERE id = 'owner'").first<{ last_meaningful_at: number | null }>();
  const since = review?.last_meaningful_at ?? owner.created_at;
  if (now - since < n.inactivityHours * HOUR_MS) return { created: false, reason: 'recently_reviewed' };
  const last = await db.prepare("SELECT MAX(created_at) AS at FROM notification_events WHERE kind = 'inactivity'").first<{ at: number | null }>();
  if (last?.at != null && now - last.at < n.cooldownHours * HOUR_MS) return { created: false, reason: 'cooldown' };
  if ((await eligibleBacklog(db, now)) === 0) return { created: false, reason: 'no_eligible_backlog' };
  const results = await db.batch(
    eventStatements(
      db,
      { dedupeKey: `inactivity:${since}:${localTime(now, n.zone).date}`, kind: 'inactivity', concernId: null, concernRevision: null, signalId: null, dueRevision: null, notBefore: now, expiresAt: now + INACTIVITY_TTL_MS },
      now,
    ),
  );
  return (results[0]?.meta.changes ?? 0) === 1 ? { created: true, reason: 'created' } : { created: false, reason: 'duplicate' };
}
