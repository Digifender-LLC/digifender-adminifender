import { applyD1Migrations } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { beforeEach } from 'vitest';

await applyD1Migrations(env.CONTROL_DB, env.CONTROL_MIGRATIONS);
await applyD1Migrations(env.ARCHIVE_0001, env.ARCHIVE_MIGRATIONS);

/** Storage persists across tests in a file, so each test starts from empty tables (plus the seeded partition) and an empty bucket. */
async function clear(db: D1Database): Promise<void> {
  const { results } = await db
    .prepare("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '\\_cf\\_%' ESCAPE '\\' AND name NOT LIKE 'd1_%' AND name NOT LIKE 'source_fts_%'")
    .all<{ name: string }>();
  let pending = results.map((row) => row.name);
  let last: string[] = [];
  // Delete children before parents without hard-coding the foreign-key order.
  const maxPasses = pending.length;
  for (let pass = 0; pending.length > 0 && pass < maxPasses; pass += 1) {
    const failed: string[] = [];
    for (const table of pending) await db.prepare(`DELETE FROM "${table}"`).run().catch((error: unknown) => failed.push(`${table}: ${String(error)}`));
    pending = failed.map((entry) => entry.split(':')[0] ?? entry);
    last = failed;
  }
  if (pending.length > 0) throw new Error(`could not clear ${last.join('; ')}`);
}

beforeEach(async () => {
  await clear(env.CONTROL_DB);
  await clear(env.ARCHIVE_0001);
  await env.CONTROL_DB.prepare("INSERT INTO archive_partitions(id, binding_name, state, created_at) VALUES (1, 'ARCHIVE_0001', 'writable', 0)").run();
  const listed = await env.ARCHIVE_BUCKET.list();
  if (listed.objects.length > 0) await env.ARCHIVE_BUCKET.delete(listed.objects.map((object) => object.key));
});
