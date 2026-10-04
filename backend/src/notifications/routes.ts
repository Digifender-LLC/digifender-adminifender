import { type AuthContext, authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { HttpError } from '../http/errors';
import { v } from '../http/validate';
import { sealToken } from './push';

export const pushBody = v.object({
  token: v.string({ min: 20, max: 4096, pattern: /^[A-Za-z0-9_:.-]+$/ }),
  permission: v.oneOf(['granted', 'denied'] as const),
  appVersion: v.string({ max: 40, pattern: /^[0-9A-Za-z.+_-]+$/ }),
});

/**
 * POST /devices/{id}/push — only through that device's own session. A rotated token replaces the old one and
 * clears an invalid mark; a denied permission is recorded so delivery reports it instead of failing silently.
 */
export async function registerPush(env: Env, now: number, body: ReturnType<typeof pushBody>, auth: AuthContext, deviceId: string) {
  if (auth.deviceId !== deviceId) throw new HttpError(403, 'wrong_device', 'A device can only register its own notifications.');
  const sealed = await sealToken(env.PUSH_TOKEN_KEY, deviceId, body.token);
  const result = await env.CONTROL_DB
    .prepare('UPDATE devices SET fcm_token_cipher = ?, push_permission = ?, push_app_version = ?, push_updated_at = ?, push_invalid_at = NULL, revision = revision + 1 WHERE id = ? AND revoked_at IS NULL')
    .bind(sealed, body.permission, body.appVersion, now, deviceId)
    .run();
  if (result.meta.changes !== 1) throw new HttpError(403, 'wrong_device', 'A device can only register its own notifications.');
  return { deviceId, push: body.permission };
}

interface EventRow {
  id: string;
  kind: string;
  concern_id: string | null;
  state: string;
  reason: string | null;
  not_before: number;
  expires_at: number;
  created_at: number;
  settled_at: number | null;
}

const iso = (ms: number | null) => (ms === null ? null : new Date(ms).toISOString());

/**
 * GET /notifications — recent logical events with per-device delivery, content-free. `sent` means the provider
 * accepted the submission, not that the owner saw it.
 */
export async function listNotifications(request: Request, env: Env, now: number) {
  await authenticate(request, env, now);
  const db = env.CONTROL_DB;
  const { results: events } = await db
    .prepare('SELECT id, kind, concern_id, state, reason, not_before, expires_at, created_at, settled_at FROM notification_events ORDER BY created_at DESC, rowid DESC LIMIT 50')
    .all<EventRow>();
  const ids = events.map((e) => e.id);
  const deliveries = ids.length
    ? (
        await db
          .prepare(`SELECT event_id, device_id, state, error_code, sent_at FROM notification_deliveries WHERE event_id IN (${ids.map(() => '?').join(',')}) ORDER BY rowid`)
          .bind(...ids)
          .all<{ event_id: string; device_id: string; state: string; error_code: string | null; sent_at: number | null }>()
      ).results
    : [];
  return {
    events: events.map((e) => ({
      id: e.id,
      kind: e.kind,
      concernId: e.concern_id,
      state: e.state,
      reason: e.reason,
      notBefore: iso(e.not_before),
      expiresAt: iso(e.expires_at),
      createdAt: iso(e.created_at),
      settledAt: iso(e.settled_at),
      deliveries: deliveries
        .filter((d) => d.event_id === e.id)
        .map((d) => ({ deviceId: d.device_id, state: d.state, errorCode: d.error_code, submittedAt: iso(d.sent_at) })),
    })),
  };
}
