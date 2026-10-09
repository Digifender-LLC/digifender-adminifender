import { env } from 'cloudflare:workers';
import { describe, expect, it } from 'vitest';
import { appendLedger, recordKey } from '../../src/deletion/ledger';
import { handleFetch } from '../../src/http/router';
import { runRecovery, verifyLedger } from '../../src/recovery/recovery';
import { T0, api, enrollOwner, ingest } from './support';

const mail = (subject: string) => ['From: billing@vendor.example', 'To: inbox@adminifender.example', `Subject: ${subject}`, `Message-ID: <${crypto.randomUUID()}@vendor.example>`, '', 'Synthetic body.', ''].join('\r\n');
const db = () => env.CONTROL_DB;
const runRow = async (id: string) => db().prepare('SELECT state, result_json FROM recovery_runs WHERE id = ?').bind(id).first<{ state: string; result_json: string }>();

describe('suppression ledger verification', () => {
  it('accepts an empty ledger and returns every source on the signed chain, skipping orphans', async () => {
    expect(await verifyLedger(env)).toEqual({ ok: true, headSeq: 0, records: 0, sourceIds: [] });
    await appendLedger(env, { operationId: 'op1', sourceIds: ['s-b', 's-a'], deletedAt: T0 });
    await env.DELETION_LEDGER.put(recordKey(2), '{"orphan":true}');
    await appendLedger(env, { operationId: 'op2', sourceIds: ['s-c'], deletedAt: T0 + 1 });
    expect(await verifyLedger(env)).toEqual({ ok: true, headSeq: 3, records: 2, sourceIds: ['s-a', 's-b', 's-c'] });
  });

  it('rejects a forged head or a changed record', async () => {
    await appendLedger(env, { operationId: 'op1', sourceIds: ['s-a'], deletedAt: T0 });
    await env.DELETION_LEDGER.put(recordKey(1), '{"seq":1,"sourceIds":[]}');
    expect(await verifyLedger(env)).toMatchObject({ ok: false, reason: 'record_missing' });
    await env.DELETION_LEDGER.put('head.json', JSON.stringify({ seq: 1, recordHash: 'x', signature: 'forged' }));
    expect(await verifyLedger(env)).toMatchObject({ ok: false, reason: 'head_invalid' });
  });
});

describe('recovery pass', () => {
  it('reapplies ledgered deletions, discards identity, and lets the owner enroll again', async () => {
    const owner = await enrollOwner();
    const kept = await ingest(mail('Kept invoice'));
    const restored = await ingest(mail('Deleted before the restore point'), { now: T0 + 1 });
    // The restore brought back a source whose deletion is only in the independent ledger.
    await appendLedger(env, { operationId: 'op-before-restore', sourceIds: [restored], deletedAt: T0 + 2 });

    expect(await runRecovery(env, 'drill-1', T0 + 10)).toBe('completed');
    const states = await db().prepare('SELECT id, state FROM source_registry ORDER BY id').all<{ id: string; state: string }>();
    expect(Object.fromEntries(states.results.map((r) => [r.id, r.state]))).toEqual({ [kept]: 'ready', [restored]: 'deleting' });
    const result = JSON.parse((await runRow('drill-1'))!.result_json);
    expect(result).toMatchObject({
      ledger: { ok: true, records: 1, deletedSources: 1 },
      reapplied: 1,
      alreadyDeleted: 0,
      identity: { passkeys: 1, sessions: 1, devices: 1, recoveryKeys: 1 },
      reconcile: { live: 1, rawChecked: 1, rawMissing: 0 },
    });
    // The result is content-free: no subjects or senders.
    expect(JSON.stringify(result)).not.toMatch(/invoice|vendor/i);

    // Restored credentials no longer work, and a second pass does nothing.
    expect((await api('GET', '/settings', { token: owner.token, now: T0 + 11 })).status).toBe(401);
    expect(await runRecovery(env, 'drill-1', T0 + 12)).toBe('already_done');

    // The operator's one-time credential enrolls a fresh passkey; the archive is still there.
    const fresh = await enrollOwner(T0 + 20);
    expect((await api('GET', '/settings', { token: fresh.token, now: T0 + 21 })).status).toBe(200);
    // While that passkey is live, another bootstrap is refused.
    const again = `afb_${'R'.repeat(43)}`;
    const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(again)))].map((b) => b.toString(16).padStart(2, '0')).join('');
    await db().prepare("INSERT INTO auth_challenges(id, kind, challenge_digest, context_json, expires_at) VALUES (?, 'bootstrap', ?, '{}', ?)").bind(crypto.randomUUID(), digest, T0 + 900_000).run();
    const refused = await api('POST', '/auth/bootstrap/options', { body: { bootstrapToken: again, deviceName: 'Other' }, now: T0 + 22 });
    expect(refused.body.error?.code).toBe('bootstrap_unavailable');
  });

  it('blocks on unverifiable ledger evidence and leaves identity alone', async () => {
    const owner = await enrollOwner();
    await env.DELETION_LEDGER.put('head.json', JSON.stringify({ seq: 4, recordHash: 'x', signature: 'forged' }));
    expect(await runRecovery(env, 'drill-2', T0 + 10)).toBe('blocked');
    expect(await runRow('drill-2')).toMatchObject({ state: 'blocked' });
    expect((await api('GET', '/settings', { token: owner.token, now: T0 + 11 })).status).toBe(200);
    expect(await runRecovery(env, 'drill-2', T0 + 12)).toBe('already_done');
  });

  it('closes the API, except liveness, while a run ID is set', async () => {
    const recovering = { ...env, RECOVERY_RUN_ID: 'drill-3' };
    const settings = await handleFetch(new Request('https://api.example.invalid/api/v1/settings'), recovering, T0);
    expect(settings.status).toBe(503);
    expect(((await settings.json()) as { error: { code: string } }).error.code).toBe('recovering');
    expect((await handleFetch(new Request('https://api.example.invalid/api/v1/health'), recovering, T0)).status).toBe(200);
    expect((await handleFetch(new Request('https://api.example.invalid/api/v1/settings'), { ...env, RECOVERY_RUN_ID: '  ' }, T0)).status).toBe(401);
  });
});
