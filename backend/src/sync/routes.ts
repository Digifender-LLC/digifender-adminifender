import { authenticate } from '../auth/sessions';
import type { Env } from '../env';
import { openCursor, sealCursor } from '../http/cursor';
import { HttpError } from '../http/errors';
import { v } from '../http/validate';
import { pageSize, rejectUnknownParams } from '../sources/routes';

/** Bootstrap snapshots live for 24 hours (lld.md#sync). */
export const SNAPSHOT_TTL_MS = 24 * 60 * 60 * 1000;

/** Last time this device finished reading the change feed (FR-032 sync health); not a meaningful review. */
async function recordCaughtUp(db: D1Database, deviceId: string, now: number): Promise<void> {
  await db.prepare('UPDATE devices SET last_sync_at = MAX(COALESCE(last_sync_at, 0), ?) WHERE id = ?').bind(now, deviceId).run();
}

interface ChangeRow {
  seq: number;
  entity_type: string;
  entity_id: string;
  revision: number;
  deleted: number;
  projection_json: string;
}

const BOOTSTRAP_DIGEST = 'sync-bootstrap';

// GET /sync/bootstrap — full current projections as of one change sequence, paged under that fixed sequence.
export async function bootstrap(request: Request, env: Env, now: number) {
  const auth = await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['limit', 'cursor']);
  const limit = pageSize(url);
  const db = env.CONTROL_DB;
  const cursorParam = url.searchParams.get('cursor');
  let position: { snapshotId: string; snapshot: number; after: number };
  if (cursorParam) {
    position = await openCursor(env.CURSOR_SECRET, BOOTSTRAP_DIGEST, cursorParam, now);
    const live = await db.prepare('SELECT 1 AS ok FROM sync_snapshots WHERE id = ? AND expires_at > ?').bind(position.snapshotId, now).first();
    if (!live) throw new HttpError(410, 'snapshot_expired', 'Start the download again.');
  } else {
    const snapshot = (await db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM changes').first<{ seq: number }>())?.seq ?? 0;
    position = { snapshotId: crypto.randomUUID(), snapshot, after: 0 };
    await db.prepare('INSERT INTO sync_snapshots(id, seq, created_at, expires_at) VALUES (?, ?, ?, ?)').bind(position.snapshotId, snapshot, now, now + SNAPSHOT_TTL_MS).run();
  }
  // The latest projection of each live entity at or before the snapshot, never current mutable rows.
  const { results } = await db
    .prepare(
      `SELECT c.seq, c.entity_type, c.entity_id, c.revision, c.deleted, c.projection_json FROM changes c
       WHERE c.seq > ? AND c.seq <= ? AND c.deleted = 0
         AND NOT EXISTS (SELECT 1 FROM changes n WHERE n.entity_type = c.entity_type AND n.entity_id = c.entity_id AND n.seq > c.seq AND n.seq <= ?)
       ORDER BY c.seq LIMIT ?`,
    )
    .bind(position.after, position.snapshot, position.snapshot, limit + 1)
    .all<ChangeRow>();
  const page = results.slice(0, limit);
  const last = page[page.length - 1];
  const nextCursor = results.length > limit && last ? await sealCursor(env.CURSOR_SECRET, BOOTSTRAP_DIGEST, { ...position, after: last.seq }, now) : null;
  if (nextCursor === null) await recordCaughtUp(db, auth.deviceId, now);
  return {
    snapshotSeq: position.snapshot,
    entities: page.map((row) => ({ entityType: row.entity_type, entityId: row.entity_id, revision: row.revision, projection: JSON.parse(row.projection_json) as unknown })),
    nextCursor,
  };
}

// GET /sync/changes?after= — ordered complete projections and tombstones; the client advances only after a whole page commits.
export async function changes(request: Request, env: Env, now: number) {
  const auth = await authenticate(request, env, now);
  const url = new URL(request.url);
  rejectUnknownParams(url, ['after', 'limit']);
  const after = v.integer({ min: 0, max: Number.MAX_SAFE_INTEGER })(Number(url.searchParams.get('after') ?? 'NaN'), '$.after');
  const limit = pageSize(url);
  const db = env.CONTROL_DB;
  const floor = (await db.prepare("SELECT seq FROM change_floor WHERE id = 'floor'").first<{ seq: number }>())?.seq ?? 0;
  if (after < floor) throw new HttpError(410, 'cursor_expired', 'Download everything again.', { floorSeq: floor });
  const { results } = await db
    .prepare('SELECT seq, entity_type, entity_id, revision, deleted, projection_json FROM changes WHERE seq > ? ORDER BY seq LIMIT ?')
    .bind(after, limit + 1)
    .all<ChangeRow>();
  const page = results.slice(0, limit);
  if (results.length <= limit) await recordCaughtUp(db, auth.deviceId, now);
  return {
    changes: page.map((row) => ({
      seq: row.seq,
      entityType: row.entity_type,
      entityId: row.entity_id,
      revision: row.revision,
      deleted: row.deleted === 1,
      projection: row.deleted === 1 ? null : (JSON.parse(row.projection_json) as unknown),
    })),
    throughSeq: page[page.length - 1]?.seq ?? after,
    hasMore: results.length > limit,
  };
}
