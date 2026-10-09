import { env } from 'cloudflare:workers';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import worker from '../../src/index';
import { privateHealth } from '../../src/diagnostics/health';
import type { Env } from '../../src/env';
import { enrollOwner, quietEnv } from './support';

const MIN = 60_000;
const scopeJson = '{"admission":"normal","kind":"archive"}';

/** The real D1 handle, except that any statement containing one of `fragments` throws as D1 would on an outage. */
function failingOn(...fragments: string[]): D1Database {
  return new Proxy(env.CONTROL_DB, {
    get(target, prop) {
      const member = Reflect.get(target, prop) as unknown;
      if (prop === 'prepare') {
        return (sql: string) => {
          if (fragments.some((fragment) => sql.includes(fragment))) throw new Error('injected D1 failure');
          return target.prepare(sql);
        };
      }
      return typeof member === 'function' ? member.bind(target) : member;
    },
  }) as D1Database;
}
const passOn = (e: Env) => worker.scheduled!({ scheduledTime: Date.now(), cron: '* * * * *', noRetry() {} } as ScheduledController, e, {} as ExecutionContext);
const observation = () => env.CONTROL_DB.prepare("SELECT observed_at FROM observations WHERE name = 'scheduled_pass'").first<{ observed_at: number }>();
const aged = () => ({ settled: Date.now() - 3 * 24 * 60 * MIN });

/** An export that failed days ago, with an object still under its prefix: the next pass has to remove it. */
async function leftoverExport(): Promise<string> {
  const id = crypto.randomUUID();
  const { settled } = aged();
  await env.CONTROL_DB.prepare("INSERT INTO exports(id, snapshot_seq, state, created_at, expires_at, scope_json, settled_at) VALUES (?, 0, 'failed', ?, ?, ?, ?)")
    .bind(id, settled, settled + 7 * 24 * 60 * MIN, scopeJson, settled)
    .run();
  await env.ARCHIVE_BUCKET.put(`exports/${id}/leftover/export.zip`, 'leftover');
  return id;
}
const leftover = async (id: string) => (await env.ARCHIVE_BUCKET.list({ prefix: `exports/${id}/` })).objects.length;

let warnings: string[];
beforeEach(async () => {
  await enrollOwner();
  warnings = [];
  vi.spyOn(console, 'warn').mockImplementation((message: unknown) => void warnings.push(String(message)));
});
afterEach(() => vi.restoreAllMocks());

describe('the scheduled pass', () => {
  it('still cleans up exports when an earlier step fails, and then fails the pass instead of recording it as healthy', async () => {
    const id = await leftoverExport();
    const e = { ...quietEnv(), CONTROL_DB: failingOn('DELETE FROM sync_snapshots') };
    await expect(passOn(e)).rejects.toThrow('scheduled pass incomplete: prune_changes');
    expect(await leftover(id)).toBe(0);
    // Only the step name is logged.
    expect(warnings.map((w) => JSON.parse(w))).toContainEqual({ event: 'scheduled_step_failed', step: 'prune_changes' });
    // The pass was not recorded, so health says so rather than reporting a healthy scheduler.
    expect(await observation()).toBeNull();
    const health = await privateHealth(quietEnv(), Date.now());
    expect(health.scheduler).toEqual({ status: 'unobserved', lastPassAt: null });
  });

  it('runs every other step when export cleanup itself fails, and names every step that failed', async () => {
    const live = Date.now();
    await env.CONTROL_DB.prepare('INSERT INTO sync_snapshots(id, seq, created_at, expires_at) VALUES (?, 0, ?, ?)').bind('expired-snapshot', live - 2 * MIN, live - MIN).run();
    const e = { ...quietEnv(), CONTROL_DB: failingOn('FROM exports e') };
    await expect(passOn(e)).rejects.toThrow('scheduled pass incomplete: expire_exports');
    // The step after the failing one and the one before it both ran: the expired snapshot was pruned.
    expect(await env.CONTROL_DB.prepare('SELECT COUNT(*) AS n FROM sync_snapshots').first<{ n: number }>()).toEqual({ n: 0 });
    expect(await observation()).toBeNull();

    const both = { ...quietEnv(), CONTROL_DB: failingOn('FROM exports e', 'DELETE FROM sync_snapshots') };
    await expect(passOn(both)).rejects.toThrow('scheduled pass incomplete: prune_changes, expire_exports');
    expect(warnings.map((w) => JSON.parse(w).step)).toEqual(expect.arrayContaining(['expire_exports', 'prune_changes']));
  });

  it('records the pass, and cleans up, when every step succeeds', async () => {
    const id = await leftoverExport();
    await passOn(quietEnv());
    expect(await leftover(id)).toBe(0);
    const recorded = await observation();
    expect(recorded).not.toBeNull();
    expect((await privateHealth(quietEnv(), recorded!.observed_at + MIN)).scheduler).toEqual({ status: 'ok', lastPassAt: new Date(recorded!.observed_at).toISOString() });
    expect(warnings).toEqual([]);
  });
});
