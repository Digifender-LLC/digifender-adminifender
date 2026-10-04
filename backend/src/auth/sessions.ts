import type { Env } from '../env';
import { unauthenticated } from '../http/errors';
import { digest, newSecret } from './tokens';

/** Baseline session defaults: access 15 minutes, refresh family 30 days absolute, recent auth 5 minutes. */
export const ACCESS_MS = 15 * 60 * 1000;
export const REFRESH_MS = 30 * 24 * 60 * 60 * 1000;
export const RECENT_AUTH_MS = 5 * 60 * 1000;

export interface IssuedTokens {
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: string;
  refreshExpiresAt: string;
}

/** Builds the INSERT for a new session row plus the plaintext tokens returned once to the client. */
export async function newSession(
  db: D1Database,
  options: {
    deviceId: string;
    authEpoch: number | 'current';
    familyId?: string;
    refreshExpires?: number;
    /** Passkey verification time carried by this session; refresh rotation preserves the previous value. */
    recentAuthAt: number | null;
    now: number;
    guard?: { sql: string; params: unknown[] };
  },
): Promise<{ statement: D1PreparedStatement; tokens: IssuedTokens; sessionId: string }> {
  const accessToken = newSecret('afa');
  const refreshToken = newSecret('afr');
  const sessionId = crypto.randomUUID();
  const refreshExpires = options.refreshExpires ?? options.now + REFRESH_MS;
  const accessExpires = Math.min(options.now + ACCESS_MS, refreshExpires);
  const epoch = options.authEpoch === 'current' ? '(SELECT auth_epoch FROM owner)' : '?';
  const params: unknown[] = [sessionId, options.deviceId];
  if (options.authEpoch !== 'current') params.push(options.authEpoch);
  params.push(options.familyId ?? crypto.randomUUID(), await digest(refreshToken), await digest(accessToken), options.now, accessExpires, refreshExpires, options.recentAuthAt);
  const guard = options.guard ? ` WHERE ${options.guard.sql}` : '';
  const statement = db
    .prepare(
      `INSERT INTO sessions(id, device_id, auth_epoch, family_id, refresh_digest, access_digest, created_at, access_expires, refresh_expires, recent_auth_at) SELECT ?, ?, ${epoch}, ?, ?, ?, ?, ?, ?, ?${guard}`,
    )
    .bind(...params, ...(options.guard?.params ?? []));
  return {
    statement,
    sessionId,
    tokens: { accessToken, refreshToken, accessExpiresAt: new Date(accessExpires).toISOString(), refreshExpiresAt: new Date(refreshExpires).toISOString() },
  };
}

export interface AuthContext {
  sessionId: string;
  familyId: string;
  deviceId: string;
  recentAuthAt: number | null;
}

/** Bearer access token → current owner session. Revoked/consumed sessions, revoked devices and old auth epochs fail. */
export async function authenticate(request: Request, env: Pick<Env, 'CONTROL_DB'>, now: number): Promise<AuthContext> {
  const header = request.headers.get('authorization') ?? '';
  const match = /^Bearer (afa_[A-Za-z0-9_-]{43})$/.exec(header);
  if (!match?.[1]) throw unauthenticated();
  const row = await env.CONTROL_DB.prepare(
    `SELECT s.id, s.family_id, s.device_id, s.recent_auth_at FROM sessions s
     JOIN devices d ON d.id = s.device_id
     JOIN owner o ON o.auth_epoch = s.auth_epoch
     WHERE s.access_digest = ? AND s.revoked_at IS NULL AND s.consumed_at IS NULL AND s.access_expires > ? AND d.revoked_at IS NULL`,
  )
    .bind(await digest(match[1]), now)
    .first<{ id: string; family_id: string; device_id: string; recent_auth_at: number | null }>();
  if (!row) throw unauthenticated();
  return { sessionId: row.id, familyId: row.family_id, deviceId: row.device_id, recentAuthAt: row.recent_auth_at };
}

export function hasRecentAuth(auth: AuthContext, now: number): boolean {
  return auth.recentAuthAt !== null && now - auth.recentAuthAt <= RECENT_AUTH_MS;
}
