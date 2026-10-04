import {
  type AuthenticationResponseJSON,
  type RegistrationResponseJSON,
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
} from '@simplewebauthn/server';
import type { Env } from '../env';
import { HttpError, unauthenticated } from '../http/errors';
import { readJson, v } from '../http/validate';
import { consumeAttempt } from './rate-limit';
import { authenticate, newSession } from './sessions';
import { digest, digestsEqual, newSecret } from './tokens';

/** Challenges expire after 5 minutes and are single-use (lld.md#identity). */
export const CHALLENGE_MS = 5 * 60 * 1000;
const OWNER_USER_HANDLE = new Uint8Array(new TextEncoder().encode('adminifender-owner'));

type ChallengeKind = 'bootstrap' | 'register' | 'authenticate' | 'recent' | 'recover';

interface ChallengeRow {
  id: string;
  challenge_digest: string;
  device_id: string | null;
  context_json: string;
}

function relyingParty(env: Env) {
  const origins = env.EXPECTED_ORIGINS.split(',').map((origin) => origin.trim()).filter(Boolean);
  if (!env.RP_ID || origins.length === 0) throw new HttpError(503, 'unavailable', 'Sign-in is not configured.', {}, true);
  return { rpID: env.RP_ID, rpName: env.RP_NAME, origins };
}

async function createChallenge(env: Env, kind: ChallengeKind, challenge: string, context: object, deviceId: string | null, now: number): Promise<string> {
  const id = crypto.randomUUID();
  await env.CONTROL_DB.prepare('INSERT INTO auth_challenges(id, kind, challenge_digest, device_id, context_json, expires_at) VALUES (?, ?, ?, ?, ?, ?)')
    .bind(id, kind, await digest(challenge), deviceId, JSON.stringify(context), now + CHALLENGE_MS)
    .run();
  return id;
}

async function liveChallenge(env: Env, id: string, kinds: ChallengeKind[], now: number): Promise<ChallengeRow & { kind: ChallengeKind }> {
  const row = await env.CONTROL_DB.prepare(
    `SELECT id, kind, challenge_digest, device_id, context_json FROM auth_challenges WHERE id = ? AND kind IN (${kinds.map(() => '?').join(', ')}) AND consumed_at IS NULL AND expires_at > ?`,
  )
    .bind(id, ...kinds, now)
    .first<ChallengeRow & { kind: ChallengeKind }>();
  if (!row) throw new HttpError(410, 'challenge_expired', 'Start again.');
  return row;
}

const matchesChallenge = (row: ChallengeRow) => async (challenge: string) => digestsEqual(await digest(challenge), row.challenge_digest);

function consume(db: D1Database, id: string, marker: string, now: number): D1PreparedStatement {
  return db.prepare('UPDATE auth_challenges SET consumed_at = ?, consumed_by = ? WHERE id = ? AND consumed_at IS NULL AND expires_at > ?').bind(now, marker, id, now);
}

const consumedGuard = (id: string, marker: string) => ({ sql: 'EXISTS (SELECT 1 FROM auth_challenges WHERE id = ? AND consumed_by = ?)', params: [id, marker] });

function andGuards(...guards: { sql: string; params: unknown[] }[]) {
  return { sql: guards.map((g) => g.sql).join(' AND '), params: guards.flatMap((g) => g.params) };
}

function registrationOptions(env: Env) {
  const rp = relyingParty(env);
  return generateRegistrationOptions({
    rpName: rp.rpName,
    rpID: rp.rpID,
    userName: 'owner',
    userDisplayName: 'Owner',
    userID: OWNER_USER_HANDLE,
    attestationType: 'none',
    timeout: CHALLENGE_MS,
    authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
  });
}

async function verifyRegistration(env: Env, row: ChallengeRow, credential: Record<string, unknown>) {
  const rp = relyingParty(env);
  try {
    const result = await verifyRegistrationResponse({
      response: credential as unknown as RegistrationResponseJSON,
      expectedChallenge: matchesChallenge(row),
      expectedOrigin: rp.origins,
      expectedRPID: rp.rpID,
      requireUserVerification: true,
    });
    if (result.verified) return result.registrationInfo;
  } catch {
    // Library error text can echo client input; only the class is reported.
  }
  throw new HttpError(400, 'verification_failed', 'The passkey could not be verified.');
}

function insertPasskey(db: D1Database, info: Awaited<ReturnType<typeof verifyRegistration>>, now: number, guard: { sql: string; params: unknown[] }) {
  return db
    .prepare(`INSERT INTO passkeys(id, owner_id, public_key, sign_count, transports_json, backup_eligible, backed_up, created_at) SELECT ?, 'owner', ?, ?, ?, ?, ?, ? WHERE ${guard.sql}`)
    .bind(
      info.credential.id,
      info.credential.publicKey,
      info.credential.counter,
      JSON.stringify(info.credential.transports ?? []),
      info.credentialDeviceType === 'multiDevice' ? 1 : 0,
      info.credentialBackedUp ? 1 : 0,
      now,
      ...guard.params,
    );
}

function insertDevice(db: D1Database, deviceId: string, name: string, capabilities: Record<string, unknown>, now: number, guard: { sql: string; params: unknown[] }) {
  return db
    .prepare(`INSERT INTO devices(id, owner_id, name, revision, capabilities_json, last_seen) SELECT ?, 'owner', ?, 1, ?, ? WHERE ${guard.sql}`)
    .bind(deviceId, name, JSON.stringify(capabilities), now, ...guard.params);
}

async function insertRecoveryKey(db: D1Database, now: number, guard: { sql: string; params: unknown[] }) {
  const secret = newSecret('afk');
  const statement = db
    .prepare(`INSERT INTO recovery_keys(id, secret_digest, created_at) SELECT ?, ?, ? WHERE ${guard.sql}`)
    .bind(crypto.randomUUID(), await digest(secret), now, ...guard.params);
  return { secret, statement };
}

const deviceName = v.string({ max: 80, pattern: /^[^\u0000-\u001f\u007f]+$/ });

// POST /auth/bootstrap/options
export async function bootstrapOptions(request: Request, env: Env, now: number) {
  await consumeAttempt(env.CONTROL_DB, request, 'bootstrap', 5, now);
  const body = await readJson(request, v.object({ bootstrapToken: v.string({ max: 60, pattern: /^afb_[A-Za-z0-9_-]{43}$/ }), deviceName }));
  const unavailable = new HttpError(403, 'bootstrap_unavailable', 'Enrollment is not available.');
  if (await env.CONTROL_DB.prepare('SELECT 1 FROM owner').first()) throw unavailable;
  const bootstrap = await env.CONTROL_DB.prepare("SELECT id FROM auth_challenges WHERE kind = 'bootstrap' AND challenge_digest = ? AND consumed_at IS NULL AND expires_at > ?")
    .bind(await digest(body.bootstrapToken), now)
    .first<{ id: string }>();
  if (!bootstrap) throw unavailable;
  const options = await registrationOptions(env);
  const challengeId = await createChallenge(env, 'register', options.challenge, { bootstrapId: bootstrap.id, deviceName: body.deviceName }, null, now);
  return { challengeId, options };
}

// POST /auth/bootstrap/verify
export async function bootstrapVerify(request: Request, env: Env, now: number) {
  const body = await readJson(request, v.object({ challengeId: v.uuid(), credential: v.record() }));
  const row = await liveChallenge(env, body.challengeId, ['register'], now);
  const context = JSON.parse(row.context_json) as { bootstrapId: string; deviceName: string };
  const info = await verifyRegistration(env, row, body.credential);

  const db = env.CONTROL_DB;
  const marker = crypto.randomUUID();
  const guard = andGuards(consumedGuard(row.id, marker), consumedGuard(context.bootstrapId, marker));
  const deviceId = crypto.randomUUID();
  const recovery = await insertRecoveryKey(db, now, guard);
  const session = await newSession(db, { deviceId, authEpoch: 1, recentAuthAt: now, now, guard });
  let results: D1Result[];
  try {
    results = await db.batch([
      consume(db, row.id, marker, now),
      consume(db, context.bootstrapId, marker, now),
      db.prepare(`INSERT INTO owner(id, created_at, auth_epoch) SELECT 'owner', ?, 1 WHERE ${guard.sql}`).bind(now, ...guard.params),
      insertPasskey(db, info, now, guard),
      insertDevice(db, deviceId, context.deviceName, {}, now, guard),
      recovery.statement,
      session.statement,
    ]);
  } catch {
    // The owner singleton or a duplicate credential rejects the whole batch.
    throw new HttpError(409, 'already_enrolled', 'An owner is already enrolled.');
  }
  if (results[0]?.meta.changes !== 1 || results[1]?.meta.changes !== 1) throw new HttpError(410, 'challenge_expired', 'Start again.');
  return { deviceId, ...session.tokens, recoverySecret: recovery.secret };
}

// POST /auth/passkey/options
export async function passkeyOptions(request: Request, env: Env, now: number) {
  const body = await readJson(request, v.object({ purpose: v.oneOf(['sign_in', 'recent'] as const), deviceId: v.optional(v.uuid()) }));
  let context: object = {};
  if (body.purpose === 'recent') {
    const auth = await authenticate(request, env, now);
    context = { sessionId: auth.sessionId };
  } else {
    await consumeAttempt(env.CONTROL_DB, request, 'passkey', 30, now);
  }
  // No allowCredentials: unauthenticated callers learn nothing about the credential inventory.
  const options = await generateAuthenticationOptions({ rpID: relyingParty(env).rpID, userVerification: 'required', timeout: CHALLENGE_MS });
  const kind = body.purpose === 'recent' ? 'recent' : 'authenticate';
  const challengeId = await createChallenge(env, kind, options.challenge, context, body.purpose === 'sign_in' ? (body.deviceId ?? null) : null, now);
  return { challengeId, options };
}

// POST /auth/passkey/verify
export async function passkeyVerify(request: Request, env: Env, now: number) {
  const body = await readJson(
    request,
    v.object({ challengeId: v.uuid(), assertion: v.record(), deviceCapabilities: v.optional(v.record()), deviceName: v.optional(deviceName) }),
  );
  const row = await liveChallenge(env, body.challengeId, ['authenticate', 'recent'], now);
  const credentialId = typeof body.assertion.id === 'string' ? body.assertion.id : '';
  const passkey = await env.CONTROL_DB.prepare('SELECT id, public_key, sign_count, transports_json FROM passkeys WHERE id = ? AND revoked_at IS NULL')
    .bind(credentialId)
    .first<{ id: string; public_key: ArrayBuffer; sign_count: number; transports_json: string }>();
  if (!passkey) throw unauthenticated();
  const rp = relyingParty(env);
  let newCounter: number;
  try {
    const result = await verifyAuthenticationResponse({
      response: body.assertion as unknown as AuthenticationResponseJSON,
      expectedChallenge: matchesChallenge(row),
      expectedOrigin: rp.origins,
      expectedRPID: rp.rpID,
      credential: { id: passkey.id, publicKey: new Uint8Array(passkey.public_key), counter: passkey.sign_count, transports: JSON.parse(passkey.transports_json) as string[] },
      requireUserVerification: true,
    });
    if (!result.verified) throw new Error('unverified');
    newCounter = result.authenticationInfo.newCounter;
  } catch {
    throw unauthenticated();
  }

  const db = env.CONTROL_DB;
  const marker = crypto.randomUUID();
  const guard = consumedGuard(row.id, marker);
  const statements = [
    consume(db, row.id, marker, now),
    db.prepare(`UPDATE passkeys SET sign_count = MAX(sign_count, ?) WHERE id = ? AND ${guard.sql}`).bind(newCounter, passkey.id, ...guard.params),
  ];

  if (row.kind === 'recent') {
    // Upgrades only the initiating session.
    const { sessionId } = JSON.parse(row.context_json) as { sessionId: string };
    statements.push(
      db.prepare(`UPDATE sessions SET recent_auth_at = ? WHERE id = ? AND revoked_at IS NULL AND consumed_at IS NULL AND ${guard.sql}`).bind(now, sessionId, ...guard.params),
    );
    const results = await db.batch(statements);
    if (results[0]?.meta.changes !== 1) throw new HttpError(410, 'challenge_expired', 'Start again.');
    if (results[2]?.meta.changes !== 1) throw unauthenticated();
    return { recentAuthAt: new Date(now).toISOString() };
  }

  let deviceId = row.device_id;
  if (deviceId) {
    const device = await db.prepare('SELECT 1 FROM devices WHERE id = ? AND revoked_at IS NULL').bind(deviceId).first();
    if (!device) deviceId = null;
  }
  if (deviceId) {
    statements.push(
      db.prepare(`UPDATE devices SET last_seen = ?, capabilities_json = COALESCE(?, capabilities_json) WHERE id = ? AND ${guard.sql}`)
        .bind(now, body.deviceCapabilities ? JSON.stringify(body.deviceCapabilities) : null, deviceId, ...guard.params),
    );
  } else {
    deviceId = crypto.randomUUID();
    statements.push(insertDevice(db, deviceId, body.deviceName ?? 'Android device', body.deviceCapabilities ?? {}, now, guard));
  }
  const session = await newSession(db, { deviceId, authEpoch: 'current', recentAuthAt: now, now, guard });
  statements.push(session.statement);
  const results = await db.batch(statements);
  if (results[0]?.meta.changes !== 1) throw new HttpError(410, 'challenge_expired', 'Start again.');
  return { deviceId, ...session.tokens };
}

// POST /auth/refresh
export async function refresh(request: Request, env: Env, now: number) {
  const match = /^Refresh (afr_[A-Za-z0-9_-]{43})$/.exec(request.headers.get('authorization') ?? '');
  if (!match?.[1]) throw unauthenticated();
  const db = env.CONTROL_DB;
  const row = await db
    .prepare(
      `SELECT s.id, s.family_id, s.device_id, s.refresh_expires, s.recent_auth_at, s.consumed_at, s.revoked_at,
              s.auth_epoch = (SELECT auth_epoch FROM owner) AS current_epoch, d.revoked_at AS device_revoked
       FROM sessions s JOIN devices d ON d.id = s.device_id WHERE s.refresh_digest = ?`,
    )
    .bind(await digest(match[1]))
    .first<{ id: string; family_id: string; device_id: string; refresh_expires: number; recent_auth_at: number | null; consumed_at: number | null; revoked_at: number | null; current_epoch: number; device_revoked: number | null }>();
  if (!row || row.revoked_at !== null) throw unauthenticated();
  if (row.consumed_at !== null) {
    // Replay of a rotated refresh token revokes its whole family.
    await db.prepare('UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL').bind(now, row.family_id).run();
    throw unauthenticated();
  }
  if (row.refresh_expires <= now || row.current_epoch !== 1 || row.device_revoked !== null) throw unauthenticated();
  const marker = crypto.randomUUID();
  const guard = { sql: 'EXISTS (SELECT 1 FROM sessions WHERE id = ? AND consumed_by = ?)', params: [row.id, marker] };
  const session = await newSession(db, {
    deviceId: row.device_id,
    authEpoch: 'current',
    familyId: row.family_id,
    refreshExpires: row.refresh_expires,
    recentAuthAt: row.recent_auth_at,
    now,
    guard,
  });
  const results = await db.batch([
    db.prepare('UPDATE sessions SET consumed_at = ?, consumed_by = ? WHERE id = ? AND consumed_at IS NULL AND revoked_at IS NULL').bind(now, marker, row.id),
    session.statement,
  ]);
  if (results[0]?.meta.changes !== 1) throw unauthenticated();
  return session.tokens;
}

// POST /auth/recovery/options
export async function recoveryOptions(request: Request, env: Env, now: number) {
  await consumeAttempt(env.CONTROL_DB, request, 'recovery', 5, now);
  const body = await readJson(request, v.object({ recoverySecret: v.string({ max: 60, pattern: /^afk_[A-Za-z0-9_-]{43}$/ }), newDeviceName: deviceName }));
  const key = await env.CONTROL_DB.prepare('SELECT id FROM recovery_keys WHERE secret_digest = ? AND used_at IS NULL').bind(await digest(body.recoverySecret)).first<{ id: string }>();
  if (!key) throw new HttpError(403, 'recovery_unavailable', 'Recovery is not available.');
  // A restricted registration challenge only; it never grants archive access by itself.
  const options = await registrationOptions(env);
  const challengeId = await createChallenge(env, 'recover', options.challenge, { recoveryKeyId: key.id, deviceName: body.newDeviceName }, null, now);
  return { challengeId, options };
}

// POST /auth/recovery/verify
export async function recoveryVerify(request: Request, env: Env, now: number) {
  const body = await readJson(request, v.object({ challengeId: v.uuid(), credential: v.record() }));
  const row = await liveChallenge(env, body.challengeId, ['recover'], now);
  const context = JSON.parse(row.context_json) as { recoveryKeyId: string; deviceName: string };
  const info = await verifyRegistration(env, row, body.credential);

  const db = env.CONTROL_DB;
  const marker = crypto.randomUUID();
  const guard = andGuards(consumedGuard(row.id, marker), { sql: 'EXISTS (SELECT 1 FROM recovery_keys WHERE id = ? AND used_by = ?)', params: [context.recoveryKeyId, marker] });
  const deviceId = crypto.randomUUID();
  const recovery = await insertRecoveryKey(db, now, guard);
  const session = await newSession(db, { deviceId, authEpoch: 'current', recentAuthAt: now, now, guard });
  // One control transaction: consume the secret, replace passkeys, bump the auth epoch, revoke every session.
  const results = await db.batch([
    consume(db, row.id, marker, now),
    db.prepare('UPDATE recovery_keys SET used_at = ?, used_by = ? WHERE id = ? AND used_at IS NULL').bind(now, marker, context.recoveryKeyId),
    db.prepare(`UPDATE passkeys SET revoked_at = ? WHERE revoked_at IS NULL AND ${guard.sql}`).bind(now, ...guard.params),
    db.prepare(`UPDATE owner SET auth_epoch = auth_epoch + 1 WHERE ${guard.sql}`).bind(...guard.params),
    db.prepare(`UPDATE sessions SET revoked_at = ? WHERE revoked_at IS NULL AND ${guard.sql}`).bind(now, ...guard.params),
    insertPasskey(db, info, now, guard),
    insertDevice(db, deviceId, context.deviceName, {}, now, guard),
    recovery.statement,
    session.statement,
  ]);
  if (results[0]?.meta.changes !== 1 || results[1]?.meta.changes !== 1) throw new HttpError(410, 'challenge_expired', 'Start again.');
  return { deviceId, ...session.tokens, recoverySecret: recovery.secret };
}

// POST /auth/logout
export async function logout(request: Request, env: Env, now: number) {
  const auth = await authenticate(request, env, now);
  await env.CONTROL_DB.prepare('UPDATE sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL').bind(now, auth.familyId).run();
  return { loggedOut: true };
}

// GET /devices
export async function listDevices(request: Request, env: Env, now: number) {
  const auth = await authenticate(request, env, now);
  const { results } = await env.CONTROL_DB.prepare('SELECT id, name, revision, last_seen, revoked_at, fcm_token_cipher IS NOT NULL AS has_push FROM devices ORDER BY id').all<{
    id: string;
    name: string;
    revision: number;
    last_seen: number | null;
    revoked_at: number | null;
    has_push: number;
  }>();
  return {
    devices: results.map((d) => ({
      id: d.id,
      name: d.name,
      revision: d.revision,
      lastSeen: d.last_seen === null ? null : new Date(d.last_seen).toISOString(),
      revoked: d.revoked_at !== null,
      pushRegistered: d.has_push === 1,
      current: d.id === auth.deviceId,
    })),
  };
}
