import { applyD1Migrations } from 'cloudflare:test';
import { env } from 'cloudflare:workers';
import { beforeEach } from 'vitest';

await applyD1Migrations(env.CONTROL_DB, env.CONTROL_MIGRATIONS);
await applyD1Migrations(env.ARCHIVE_0001, env.ARCHIVE_MIGRATIONS);

/** Storage persists across tests in a file, so each test starts from empty tables and an empty bucket. */
async function clear(db: D1Database): Promise<void> {
  const { results } = await db
    .prepare("SELECT name FROM sqlite_schema WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE '\\_cf\\_%' ESCAPE '\\' AND name NOT LIKE 'd1_%' AND name NOT LIKE 'source_fts_%'")
    .all<{ name: string }>();
  let pending = results.map((row) => row.name);
  // Delete children before parents without hard-coding the foreign-key order.
  for (let pass = 0; pending.length > 0 && pass < pending.length + 1; pass += 1) {
    const failed: string[] = [];
    for (const table of pending) await db.prepare(`DELETE FROM "${table}"`).run().catch(() => failed.push(table));
    pending = failed;
  }
  if (pending.length > 0) throw new Error(`could not clear ${pending.join(', ')}`);
}

beforeEach(async () => {
  await clear(env.CONTROL_DB);
  await clear(env.ARCHIVE_0001);
  const listed = await env.ARCHIVE_BUCKET.list();
  if (listed.objects.length > 0) await env.ARCHIVE_BUCKET.delete(listed.objects.map((object) => object.key));
});
