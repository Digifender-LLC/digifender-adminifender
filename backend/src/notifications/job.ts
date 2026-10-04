import type { Env } from '../env';
import { loadSettings } from '../inference/settings';
import { RUNNING_GUARD } from '../inference/job';
import { CLAIM_GUARD, type ClaimedJob, type JobResult, guardParams } from '../jobs/runner';
import { HAS_LIVE_SOURCE, eligibleBacklog } from './events';
import { type EventKind, type PushOutcome, openToken, pushSender } from './push';
import { inQuietHours, quietEnd } from './zone';

interface EventRow {
  id: string;
  kind: EventKind;
  concern_id: string | null;
  signal_id: string | null;
  due_revision: number | null;
  state: string;
  expires_at: number;
  created_at: number;
}

/** Where the app opens: the concern, or the briefing for an inactivity reminder. Opaque, never content. */
export const BRIEFING_DESTINATION = 'briefing';

/** Rechecks current state at dispatch (lld.md#notifications). Returns a suppression reason, or null to send. */
async function suppression(db: D1Database, event: EventRow, now: number): Promise<string | null> {
  if (event.kind === 'inactivity') {
    const review = await db.prepare("SELECT last_meaningful_at FROM review_state WHERE id = 'owner'").first<{ last_meaningful_at: number | null }>();
    if (review?.last_meaningful_at != null && review.last_meaningful_at > event.created_at) return 'reviewed';
    return (await eligibleBacklog(db, now)) === 0 ? 'no_eligible_backlog' : null;
  }
  const concern = await db
    .prepare(`SELECT c.status, c.critical_signal_id, c.acknowledged_at, c.due_at, c.due_revision, ${HAS_LIVE_SOURCE} AS live FROM concerns c WHERE c.id = ?`)
    .bind(event.concern_id)
    .first<{ status: string; critical_signal_id: string | null; acknowledged_at: number | null; due_at: number | null; due_revision: number; live: number }>();
  if (!concern) return 'concern_missing';
  if (concern.live !== 1) return 'source_unavailable';
  if (concern.status === 'resolved') return 'resolved';
  if (concern.status === 'snoozed') return 'snoozed';
  if (event.kind === 'critical') {
    if (concern.critical_signal_id !== event.signal_id) return 'superseded';
    if (concern.acknowledged_at !== null) return 'acknowledged';
    return null;
  }
  if (concern.due_revision !== event.due_revision || concern.due_at === null) return 'superseded';
  return null;
}

interface Delivery {
  device_id: string;
  state: string;
  error_code: string | null;
}

function aggregate(deliveries: Delivery[]): { state: 'sent' | 'uncertain' | 'failed'; reason: string | null } {
  if (deliveries.some((d) => d.state === 'sent')) return { state: 'sent', reason: null };
  if (deliveries.some((d) => d.state === 'uncertain')) return { state: 'uncertain', reason: 'send_uncertain' };
  return { state: 'failed', reason: deliveries[0]?.error_code ?? 'no_push_device' };
}

const deliveryState = (outcome: PushOutcome): { state: string; code: string | null; receipt: string | null } => {
  switch (outcome.outcome) {
    case 'sent':
      return { state: 'sent', code: null, receipt: outcome.receipt };
    case 'invalid_token':
      return { state: 'failed', code: 'invalid_token', receipt: null };
    case 'rejected':
      return { state: 'failed', code: outcome.code, receipt: null };
    case 'uncertain':
      return { state: 'uncertain', code: 'send_uncertain', receipt: null };
  }
};

/**
 * Dispatches one notification event to every registered device. Not replay-safe once a send starts: each
 * delivery is recorded as dispatching before the provider call, and an interrupted run turns it into an
 * uncertain delivery instead of sending the same event again. A provider receipt means submission only.
 */
export async function notifyJob(job: ClaimedJob, env: Env, now: number): Promise<JobResult> {
  const db = env.CONTROL_DB;
  const eventId = (job.input as { eventId?: unknown }).eventId;
  if (typeof eventId !== 'string') return { kind: 'fail', errorCode: 'invalid_input' };
  const event = await db
    .prepare('SELECT id, kind, concern_id, signal_id, due_revision, state, expires_at, created_at FROM notification_events WHERE id = ?')
    .bind(eventId)
    .first<EventRow>();
  if (!event) return { kind: 'fail', errorCode: 'missing_event' };
  if (event.state !== 'pending') return { kind: 'complete', output: { state: event.state } };

  const settle = (state: string, reason: string | null): JobResult => ({
    kind: 'complete',
    output: { state, reason },
    statements: [db.prepare(`UPDATE notification_events SET state = ?, reason = ?, settled_at = ? WHERE id = ? AND state = 'pending' AND ${CLAIM_GUARD}`).bind(state, reason, now, event.id, ...guardParams(job))],
  });

  // A run that stopped mid-send: its deliveries are uncertain, and the event is not sent again.
  await db
    .prepare(`UPDATE notification_deliveries SET state = 'uncertain', error_code = 'interrupted' WHERE event_id = ? AND state = 'dispatching' AND ${RUNNING_GUARD}`)
    .bind(event.id, ...guardParams(job))
    .run();
  const prior = (await db.prepare('SELECT device_id, state, error_code FROM notification_deliveries WHERE event_id = ? ORDER BY rowid').bind(event.id).all<Delivery>()).results;
  if (prior.length > 0) {
    const outcome = aggregate(prior);
    return settle(outcome.state, outcome.reason);
  }

  if (now >= event.expires_at) return settle('suppressed', 'expired');
  const reason = await suppression(db, event, now);
  if (reason) return settle('suppressed', reason);
  const { notifications } = await loadSettings(db);
  if (!notifications.channels[event.kind]) return settle('suppressed', 'channel_disabled');
  // Rule-confirmed critical events bypass quiet hours; due and inactivity reminders wait for them to end.
  if (event.kind !== 'critical' && inQuietHours(now, notifications.zone, notifications.quietHours)) {
    const until = quietEnd(now, notifications.zone, notifications.quietHours);
    if (until >= event.expires_at) return settle('suppressed', 'quiet_hours');
    return { kind: 'defer', until };
  }

  const sender = pushSender(env);
  if (!sender) return settle('failed', 'push_unavailable');
  const { results: devices } = await db
    .prepare(
      // A signed-out device keeps its registration row but has no live session, so it receives nothing.
      `SELECT id, fcm_token_cipher, push_updated_at FROM devices d
       WHERE revoked_at IS NULL AND fcm_token_cipher IS NOT NULL AND push_permission = 'granted' AND push_invalid_at IS NULL
         AND EXISTS (SELECT 1 FROM sessions s WHERE s.device_id = d.id AND s.revoked_at IS NULL AND s.consumed_at IS NULL AND s.refresh_expires > ?
                     AND s.auth_epoch = (SELECT auth_epoch FROM owner))
       ORDER BY id`,
    )
    .bind(now)
    .all<{ id: string; fcm_token_cipher: number[]; push_updated_at: number }>();
  if (devices.length === 0) return settle('failed', 'no_push_device');

  const message = {
    eventId: event.id,
    kind: event.kind,
    destinationId: event.concern_id ?? BRIEFING_DESTINATION,
    critical: event.kind === 'critical',
    ttlSeconds: Math.max(1, Math.floor((event.expires_at - now) / 1000)),
  };
  const deliveries: Delivery[] = [];
  for (const device of devices) {
    const token = await openToken(env.PUSH_TOKEN_KEY, device.id, device.fcm_token_cipher);
    if (token === null) {
      await db
        .prepare(`INSERT INTO notification_deliveries(event_id, device_id, state, error_code) SELECT ?, ?, 'failed', 'token_unreadable' WHERE ${RUNNING_GUARD}`)
        .bind(event.id, device.id, ...guardParams(job))
        .run();
      deliveries.push({ device_id: device.id, state: 'failed', error_code: 'token_unreadable' });
      continue;
    }
    const claimed = await db
      .prepare(`INSERT INTO notification_deliveries(event_id, device_id, state, dispatched_at) SELECT ?, ?, 'dispatching', ? WHERE ${RUNNING_GUARD}`)
      .bind(event.id, device.id, now, ...guardParams(job))
      .run();
    // Lost the claim: another run owns this event now, so nothing more is sent from here.
    if (claimed.meta.changes !== 1) return { kind: 'retry', errorCode: 'lease_lost' };
    let outcome: PushOutcome;
    try {
      outcome = await sender.send(token, message);
    } catch {
      outcome = { outcome: 'uncertain' };
    }
    const recorded = deliveryState(outcome);
    const writes = [
      db
        .prepare("UPDATE notification_deliveries SET state = ?, error_code = ?, provider_receipt = ?, sent_at = ? WHERE event_id = ? AND device_id = ? AND state = 'dispatching'")
        .bind(recorded.state, recorded.code, recorded.receipt, recorded.state === 'sent' ? now : null, event.id, device.id),
    ];
    // Authoritative provider answer: disable this registration until the phone registers a new token.
    if (outcome.outcome === 'invalid_token') {
      // Keyed by registration time, so a token rotated meanwhile is not disabled.
      writes.push(db.prepare('UPDATE devices SET push_invalid_at = ? WHERE id = ? AND push_updated_at = ?').bind(now, device.id, device.push_updated_at));
    }
    await db.batch(writes);
    deliveries.push({ device_id: device.id, state: recorded.state, error_code: recorded.code });
  }
  const outcome = aggregate(deliveries);
  return settle(outcome.state, outcome.reason);
}
