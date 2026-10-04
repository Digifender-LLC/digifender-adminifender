/** Historical projections and tombstones are kept 90 days (baseline.md defaults). */
export const CHANGE_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
export const PRUNE_BATCH = 500;

/**
 * Prunes change history without losing state: the latest projection of every live entity is kept however old,
 * versions a live bootstrap snapshot still pages are kept, and dropping a tombstone raises the cursor floor so
 * older clients rebootstrap instead of missing the deletion (lld.md#sync).
 */
export async function pruneChanges(db: D1Database, now = Date.now()): Promise<{ superseded: number; tombstones: number }> {
  await db.prepare('DELETE FROM sync_snapshots WHERE expires_at <= ?').bind(now).run();
  const live = await db.prepare('SELECT MIN(seq) AS low, MAX(seq) AS high FROM sync_snapshots').first<{ low: number | null; high: number | null }>();
  const cutoff = now - CHANGE_RETENTION_MS;
  // A superseded row is unneeded when its replacement is visible to every live snapshot, or no snapshot can see it.
  const superseded = await db
    .prepare(
      `DELETE FROM changes WHERE seq IN (
         SELECT c.seq FROM changes c WHERE c.created_at < ? AND EXISTS (
           SELECT 1 FROM changes n WHERE n.entity_type = c.entity_type AND n.entity_id = c.entity_id AND n.seq > c.seq
             AND (? IS NULL OR n.seq <= ? OR c.seq > ?))
         ORDER BY c.seq LIMIT ?)`,
    )
    .bind(cutoff, live?.low ?? null, live?.low ?? null, live?.high ?? 0, PRUNE_BATCH)
    .run();
  const { results: tombstones } = await db
    .prepare(
      `SELECT c.seq FROM changes c WHERE c.deleted = 1 AND c.created_at < ?
         AND NOT EXISTS (SELECT 1 FROM changes n WHERE n.entity_type = c.entity_type AND n.entity_id = c.entity_id AND n.seq > c.seq)
       ORDER BY c.seq LIMIT ?`,
    )
    .bind(cutoff, PRUNE_BATCH)
    .all<{ seq: number }>();
  const top = tombstones[tombstones.length - 1]?.seq;
  if (top !== undefined) {
    await db.batch([
      db.prepare(`DELETE FROM changes WHERE seq IN (${tombstones.map(() => '?').join(',')})`).bind(...tombstones.map((t) => t.seq)),
      db.prepare("INSERT INTO change_floor(id, seq) VALUES ('floor', ?) ON CONFLICT(id) DO UPDATE SET seq = MAX(change_floor.seq, excluded.seq)").bind(top),
    ]);
  }
  return { superseded: superseded.meta.changes, tombstones: tombstones.length };
}
