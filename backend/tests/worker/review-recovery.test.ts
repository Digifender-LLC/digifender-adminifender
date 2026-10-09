import { env } from 'cloudflare:workers';
import { beforeEach, describe, expect, it } from 'vitest';
import { newSession } from '../../src/auth/sessions';
import type { Env } from '../../src/env';
import { T0, api, enrollOwner, expectContract, expectContractViolation, ingest, runPending } from './support';

const MIN = 60_000;
let token: string;
let keyCount = 0;
const key = () => `00000000-0000-4000-a100-${String((keyCount += 1)).padStart(12, '0')}`;

const mail = (subject: string, body = 'Account number 99-1234 is overdue.', attachment = 'attached secret words') =>
  [
    'From: ops@vendor.example',
    'To: inbox@adminifender.example',
    `Subject: ${subject}`,
    'MIME-Version: 1.0',
    'Content-Type: multipart/mixed; boundary="b1"',
    '',
    '--b1',
    'Content-Type: text/plain',
    '',
    body,
    '--b1',
    'Content-Type: text/plain; name="note.txt"',
    'Content-Disposition: attachment; filename="note.txt"',
    '',
    attachment,
    '--b1--',
    '',
  ].join('\r\n');

async function source(subject: string, now = T0): Promise<string> {
  const id = await ingest(mail(subject), { now });
  await runPending('triage', now);
  await runPending('extract', now);
  return id;
}
async function prepare(sourceIds: string[], now = T0) {
  const created = await api('POST', '/reviews', { token, key: key(), now, body: { effect: 'archive.delete', params: { sourceIds } } });
  expect(created.status).toBe(200);
  expectContract('review-prepared', created.body.data);
  return created.body.data;
}
const confirm = (reviewId: string, paramsDigest: string, options: { now?: number; env?: Env; token?: string; key?: string } = {}) =>
  api('POST', `/reviews/${reviewId}/confirm`, { token: options.token ?? token, key: options.key ?? key(), now: options.now ?? T0, body: { paramsDigest }, ...(options.env ? { env: options.env } : {}) });
async function readback(reviewId: string, now = T0, as = token) {
  const res = await api('GET', `/reviews/${reviewId}`, { token: as, now });
  expect(res.status).toBe(200);
  expectContract('review-readback', res.body.data);
  return res.body.data;
}
async function operation(operationId: string, now = T0) {
  const res = await api('GET', `/operations/${operationId}`, { token, now });
  expect(res.status).toBe(200);
  expectContract('operation', res.body.data);
  return res.body.data;
}

beforeEach(async () => {
  token = (await enrollOwner()).token;
});

describe('GET /reviews/{id}', () => {
  it('reads an open review without parameters, previews or any authority', async () => {
    const victim = await source('Private invoice 4471');
    const planned = await prepare([victim]);
    const before = await env.CONTROL_DB.prepare('SELECT (SELECT COUNT(*) FROM action_reviews) AS r, (SELECT COUNT(*) FROM operations) AS o, (SELECT COUNT(*) FROM mutations) AS m, (SELECT COUNT(*) FROM changes) AS c').first();

    const state = await readback(planned.reviewId, T0 + 1000);
    expect(state).toEqual({
      reviewId: planned.reviewId,
      effect: 'archive.delete',
      paramsDigest: planned.paramsDigest,
      state: 'open',
      createdAt: new Date(T0).toISOString(),
      expiresAt: planned.expiresAt,
      requiresRecentAuth: true,
      consumedOperationId: null,
      operationState: null,
    });
    const text = JSON.stringify(state);
    for (const leak of ['4471', 'Private invoice', victim, 'sourceIds', 'preview', 'preconditions']) expect(text).not.toContain(leak);
    // Reading changed nothing and needed no Idempotency-Key.
    expect(await env.CONTROL_DB.prepare('SELECT (SELECT COUNT(*) FROM action_reviews) AS r, (SELECT COUNT(*) FROM operations) AS o, (SELECT COUNT(*) FROM mutations) AS m, (SELECT COUNT(*) FROM changes) AS c').first()).toEqual(before);
    expect((await api('GET', `/sources/${victim}`, { token })).status).toBe(200);
  });

  it('shows expiry, and a review that failed to confirm stays open', async () => {
    const victim = await source('Private invoice 4471');
    const planned = await prepare([victim]);
    expect((await readback(planned.reviewId, T0 + 9 * MIN)).state).toBe('open');
    expect((await readback(planned.reviewId, T0 + 10 * MIN)).state).toBe('expired');

    const again = await prepare([victim], T0 + MIN);
    const broken = { ...env, DELETION_LEDGER: { get: async () => null, put: async () => { throw new Error('r2 down'); } } as unknown as R2Bucket };
    expect((await confirm(again.reviewId, again.paramsDigest, { env: broken, now: T0 + 2 * MIN })).status).toBe(503);
    // Nothing was committed, so the review is not consumed and can still be confirmed.
    expect(await readback(again.reviewId, T0 + 3 * MIN)).toMatchObject({ state: 'open', consumedOperationId: null });

    await api('POST', `/sources/${victim}/quarantine`, { token, key: key(), now: T0 + 3 * MIN, body: { expectedRevision: (await api('GET', `/sources/${victim}`, { token, now: T0 + 3 * MIN })).body.data.source.revision } });
    expect((await confirm(again.reviewId, again.paramsDigest, { now: T0 + 3 * MIN })).body.error?.code).toBe('stale_review');
    expect(await readback(again.reviewId, T0 + 3 * MIN)).toMatchObject({ state: 'open' });
  });

  it('recovers the receipt after a lost confirmation once recent authentication has expired, without confirming again', async () => {
    const a = await source('Private invoice 4471');
    const b = await source('Second private note', T0 + 1000);
    const planned = await prepare([a, b]);
    // The confirmation commits, but its response never reaches the phone.
    const lost = await confirm(planned.reviewId, planned.paramsDigest);
    expect(lost.status).toBe(200);
    expectContract('review-confirmed', lost.body.data);

    // Six minutes on, recent passkey authentication has expired and the review itself is past its window.
    const later = T0 + 11 * MIN;
    const recovered = await readback(planned.reviewId, later);
    expect(recovered).toMatchObject({ state: 'consumed', consumedOperationId: lost.body.data.operationId, operationState: 'succeeded' });
    const op = await operation(recovered.consumedOperationId, later);
    expect(op).toMatchObject({ id: lost.body.data.operationId, reviewId: planned.reviewId, state: 'succeeded', receipt: lost.body.data.receipt });

    // Reading gave no authority: a new confirmation needs recent authentication and the review is consumed.
    const retry = await confirm(planned.reviewId, planned.paramsDigest, { now: later });
    expect(retry.status).toBe(403);
    expect(retry.body.error?.code).toBe('recent_auth_required');
    expect((await readback(planned.reviewId, later)).state).toBe('consumed');
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM operations').first()).toEqual({ n: 1 });
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM deletion_ledger').first()).toEqual({ n: 2 });
    // Even with recent authentication a second confirmation names the operation instead of repeating it.
    const second = await confirm(planned.reviewId, planned.paramsDigest);
    expect(second.status).toBe(409);
    expect(second.body.error).toMatchObject({ code: 'review_consumed', details: { operationId: lost.body.data.operationId } });
  });

  it('distinguishes consumed from unconsumed reviews of the same effect', async () => {
    const a = await source('First');
    const b = await source('Second', T0 + 1000);
    const used = await prepare([a]);
    const unused = await prepare([b]);
    await confirm(used.reviewId, used.paramsDigest);
    expect(await readback(used.reviewId)).toMatchObject({ state: 'consumed', operationState: 'succeeded' });
    expect(await readback(unused.reviewId)).toMatchObject({ state: 'open', consumedOperationId: null, operationState: null });
    expect((await api('GET', `/sources/${b}`, { token })).status).toBe(200);
  });

  it('reads a consumed device revocation the same way', async () => {
    const deviceId = crypto.randomUUID();
    await env.CONTROL_DB.prepare("INSERT INTO devices(id, owner_id, name, capabilities_json) VALUES (?, 'owner', 'Old tablet', '{}')").bind(deviceId).run();
    const planned = await api('POST', '/reviews', { token, key: key(), body: { effect: 'device.revoke', params: { deviceId } } });
    expectContract('review-prepared', planned.body.data);
    const done = await confirm(planned.body.data.reviewId, planned.body.data.paramsDigest);
    expectContract('review-confirmed', done.body.data);
    const state = await readback(planned.body.data.reviewId, T0 + 14 * MIN);
    expect(state).toMatchObject({ effect: 'device.revoke', state: 'consumed', consumedOperationId: done.body.data.operationId });
    const op = await operation(done.body.data.operationId, T0 + 14 * MIN);
    expect(op).toMatchObject({ cleanup: null, receipt: { deviceRevoked: deviceId } });
  });

  it('keeps deleted content out of the readback and the operation', async () => {
    const victim = await source('Private invoice 4471');
    const planned = await prepare([victim]);
    const done = await confirm(planned.reviewId, planned.paramsDigest);
    expect(await runPending('cleanup')).toEqual(['complete']);
    const texts = [JSON.stringify(await readback(planned.reviewId, T0 + 14 * MIN)), JSON.stringify(await operation(done.body.data.operationId, T0 + 14 * MIN))];
    for (const text of texts) for (const leak of ['4471', 'Private invoice', 'overdue', 'secret words', 'vendor.example', 'preview']) expect(text).not.toContain(leak);
    // The stored review keeps identifiers only.
    const stored = await env.CONTROL_DB.prepare('SELECT params_json || digest || preconditions_json AS j FROM action_reviews WHERE id = ?').bind(planned.reviewId).first<{ j: string }>();
    expect(stored!.j).not.toContain('Private invoice');
  });

  it('is only readable with an owner access session, and reports unknown reviews as absent', async () => {
    const victim = await source('Private invoice 4471');
    const planned = await prepare([victim]);
    const path = `/reviews/${planned.reviewId}`;
    expect((await api('GET', path)).status).toBe(401);
    expect((await api('GET', path, { token: `afa_${'x'.repeat(43)}` })).status).toBe(401);
    expect((await api('GET', path, { auth: 'Refresh afr_nonsense' })).status).toBe(401);
    expect((await api('GET', path, { token, now: T0 + 16 * MIN })).status).toBe(401);

    // A revoked device's session can no longer read it.
    const deviceId = crypto.randomUUID();
    await env.CONTROL_DB.prepare("INSERT INTO devices(id, owner_id, name, capabilities_json) VALUES (?, 'owner', 'Old tablet', '{}')").bind(deviceId).run();
    const session = await newSession(env.CONTROL_DB, { deviceId, authEpoch: 'current', recentAuthAt: null, now: T0 });
    await session.statement.run();
    const other = session.tokens.accessToken;
    expect((await api('GET', path, { token: other })).status).toBe(200);
    const revoke = await api('POST', '/reviews', { token, key: key(), body: { effect: 'device.revoke', params: { deviceId } } });
    await confirm(revoke.body.data.reviewId, revoke.body.data.paramsDigest);
    expect((await api('GET', path, { token: other })).status).toBe(401);

    // Not found is not evidence either way: the client keeps its own uncertain state.
    const missing = await api('GET', `/reviews/${crypto.randomUUID()}`, { token });
    expect(missing.status).toBe(404);
    expect(missing.body.error?.code).toBe('not_found');
    expect((await api('GET', `/reviews/${planned.reviewId.replace(/.$/, '0')}x`, { token })).status).toBe(404);
  });

  it('refuses shapes that would carry content or authority', async () => {
    const victim = await source('Private invoice 4471');
    const planned = await prepare([victim]);
    const state = await readback(planned.reviewId);
    expectContractViolation('review-readback', { ...state, preview: planned.preview });
    expectContractViolation('review-readback', { ...state, params: planned.params });
    expectContractViolation('review-readback', { ...state, state: 'consumed' });
    expectContractViolation('review-readback', { ...state, requiresRecentAuth: false });
    expectContractViolation('review-prepared', { ...planned, requiresRecentAuth: false });
  });
});

describe('cleanup status on GET /operations/{id}', () => {
  const setNextAt = (sourceId: string, at: number) => env.CONTROL_DB.prepare("UPDATE jobs SET next_at = ? WHERE dedupe_key = ?").bind(at, `cleanup:${sourceId}`).run();

  it('reports pending, partial and complete from the deletion ledger', async () => {
    const a = await source('Private invoice 4471');
    const b = await source('Second private note', T0 + 1000);
    const planned = await prepare([a, b]);
    const done = await confirm(planned.reviewId, planned.paramsDigest);
    const id = done.body.data.operationId;
    expect((await operation(id)).cleanup).toEqual({ scope: 'live_archive', state: 'pending', total: 2, completed: 0, failed: 0, unrecorded: 0 });

    await setNextAt(b, T0 + 14 * MIN);
    expect(await runPending('cleanup')).toEqual(['complete']);
    expect((await operation(id)).cleanup).toEqual({ scope: 'live_archive', state: 'partial', total: 2, completed: 1, failed: 0, unrecorded: 0 });
    // The receipt did not change: it still only says hidden, tombstoned and recorded.
    expect((await operation(id)).receipt).toEqual(done.body.data.receipt);

    expect(await runPending('cleanup', T0 + 14 * MIN)).toEqual(['complete']);
    expect((await operation(id, T0 + 14 * MIN)).cleanup).toEqual({ scope: 'live_archive', state: 'complete', total: 2, completed: 2, failed: 0, unrecorded: 0 });
  });

  it('reports a cleanup that gave up as failed, and a source the ledger lost as unknown', async () => {
    const a = await source('Private invoice 4471');
    const b = await source('Second private note', T0 + 1000);
    const planned = await prepare([a, b]);
    const id = (await confirm(planned.reviewId, planned.paramsDigest)).body.data.operationId;
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'dead', error_code = 'archive_unavailable' WHERE dedupe_key = ?").bind(`cleanup:${a}`).run();
    expect((await operation(id)).cleanup).toMatchObject({ state: 'failed', total: 2, completed: 0, failed: 1, unrecorded: 0 });
    // A source with no cleanup job at all can never finish by itself.
    await env.CONTROL_DB.prepare('DELETE FROM outbox WHERE reference_id IN (SELECT id FROM jobs WHERE dedupe_key = ?)').bind(`cleanup:${b}`).run();
    await env.CONTROL_DB.prepare('DELETE FROM jobs WHERE dedupe_key = ?').bind(`cleanup:${b}`).run();
    expect((await operation(id)).cleanup).toMatchObject({ state: 'failed', failed: 2 });

    // When the ledger no longer accounts for a source, nothing is claimed about it.
    await env.CONTROL_DB.prepare('DELETE FROM deletion_ledger WHERE source_id = ?').bind(b).run();
    const unknown = (await operation(id)).cleanup;
    expect(unknown).toEqual({ scope: 'live_archive', state: 'unknown', total: 2, completed: 0, failed: 1, unrecorded: 1 });
    expectContractViolation('operation', { ...(await operation(id)), cleanup: { ...unknown, state: 'complete' } });
  });

  it('reports unknown, not pending forever, when the cleanup job finished but the ledger row never completed', async () => {
    const a = await source('Private invoice 4471');
    const b = await source('Second private note', T0 + 1000);
    const planned = await prepare([a, b]);
    const id = (await confirm(planned.reviewId, planned.paramsDigest)).body.data.operationId;
    // Source `a` reached `deleted` by another path, so its cleanup job ends as `already_deleted` and never touches the ledger.
    await env.CONTROL_DB.prepare("UPDATE source_registry SET state = 'deleted' WHERE id = ?").bind(a).run();
    await setNextAt(b, T0 + 14 * MIN);
    expect(await runPending('cleanup')).toEqual(['complete']);
    expect(await env.CONTROL_DB.prepare('SELECT output_json FROM jobs WHERE dedupe_key = ?').bind(`cleanup:${a}`).first()).toEqual({ output_json: '{"skipped":"already_deleted"}' });
    expect((await env.CONTROL_DB.prepare('SELECT state FROM deletion_ledger WHERE source_id = ?').bind(a).first<{ state: string }>())?.state).toBe('pending');
    const inconsistent = (await operation(id)).cleanup;
    expect(inconsistent).toEqual({ scope: 'live_archive', state: 'unknown', total: 2, completed: 0, failed: 0, unrecorded: 1 });
    // Once the other source is done the contradiction is still reported, never complete and never pending.
    expect(await runPending('cleanup', T0 + 14 * MIN)).toEqual(['complete']);
    expect((await operation(id, T0 + 14 * MIN)).cleanup).toEqual({ scope: 'live_archive', state: 'unknown', total: 2, completed: 1, failed: 0, unrecorded: 1 });
    // A job waiting for its next retry is not a contradiction: it stays pending.
    const c = await source('Third note', T0 + 2000);
    const again = await prepare([c], T0 + 2000);
    const retryId = (await confirm(again.reviewId, again.paramsDigest, { now: T0 + 2000 })).body.data.operationId;
    await env.CONTROL_DB.prepare("UPDATE jobs SET state = 'pending', attempt = 2, next_at = ?, error_code = 'archive_unavailable' WHERE dedupe_key = ?").bind(T0 + 3 * MIN, `cleanup:${c}`).run();
    expect((await operation(retryId, T0 + 2000)).cleanup).toEqual({ scope: 'live_archive', state: 'pending', total: 1, completed: 0, failed: 0, unrecorded: 0 });
  });

  it('binds cleanup to the operation that deleted the sources', async () => {
    const a = await source('Private invoice 4471');
    const b = await source('Second private note', T0 + 1000);
    const first = await prepare([a]);
    const firstId = (await confirm(first.reviewId, first.paramsDigest)).body.data.operationId;
    const second = await prepare([b]);
    const secondId = (await confirm(second.reviewId, second.paramsDigest)).body.data.operationId;
    await setNextAt(a, T0 + 14 * MIN);
    await runPending('cleanup');
    expect((await operation(secondId)).cleanup).toMatchObject({ state: 'complete', total: 1, completed: 1 });
    expect((await operation(firstId)).cleanup).toMatchObject({ state: 'pending', total: 1, completed: 0 });

    // A ledger row written by some other operation does not count for this one.
    await env.CONTROL_DB.prepare("UPDATE deletion_ledger SET targets_json = ? WHERE source_id = ?").bind(JSON.stringify({ operationId: crypto.randomUUID() }), a).run();
    expect((await operation(firstId)).cleanup).toMatchObject({ state: 'unknown', unrecorded: 1 });
  });

  it('has no cleanup for other effects, and the report never speaks for backups', async () => {
    const deviceId = crypto.randomUUID();
    await env.CONTROL_DB.prepare("INSERT INTO devices(id, owner_id, name, capabilities_json) VALUES (?, 'owner', 'Old tablet', '{}')").bind(deviceId).run();
    const planned = await api('POST', '/reviews', { token, key: key(), body: { effect: 'device.revoke', params: { deviceId } } });
    const done = await confirm(planned.body.data.reviewId, planned.body.data.paramsDigest);
    expect((await operation(done.body.data.operationId)).cleanup).toBeNull();
    const a = await source('Private invoice 4471');
    const del = await prepare([a]);
    const id = (await confirm(del.reviewId, del.paramsDigest)).body.data.operationId;
    const cleanup = (await operation(id)).cleanup;
    expect(cleanup.scope).toBe('live_archive');
    expectContractViolation('operation', { ...(await operation(id)), cleanup: { ...cleanup, scope: 'backups' } });
    expectContractViolation('operation', { ...(await operation(id)), cleanup: null });
    expect((await api('GET', `/operations/${crypto.randomUUID()}`, { token })).status).toBe(404);
  });
});
