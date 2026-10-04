import type { Env } from '../env';
import { notFound } from '../http/errors';
import { resolvePartition } from '../archive/partitions';

export interface RegistryRow {
  id: string;
  kind: string;
  admission: 'normal' | 'quarantine';
  privacy: string;
  privacy_floor: string;
  state: string;
  revision: number;
  received_at: number;
  partition_id: number | null;
  binding_name: string | null;
}

export const REGISTRY_COLUMNS =
  'r.id, r.kind, r.admission, r.privacy, r.privacy_floor, r.state, r.revision, r.received_at, r.partition_id, p.binding_name';
export const REGISTRY_FROM = 'source_registry r LEFT JOIN archive_partitions p ON p.id = r.partition_id';

/** Sanitized source metadata shared by API responses and sync change projections. */
export function sourceProjection(row: RegistryRow, archive?: { subject: string; envelope_json: string } | null) {
  const envelope = archive ? (JSON.parse(archive.envelope_json) as { from?: string[]; to?: string[] }) : null;
  return {
    id: row.id,
    kind: row.kind,
    state: row.state,
    admission: row.admission,
    privacy: row.privacy,
    revision: row.revision,
    receivedAt: new Date(row.received_at).toISOString(),
    subject: archive?.subject ?? null,
    from: envelope?.from ?? [],
    to: envelope?.to ?? [],
  };
}

/** Current control authority for a visible source; deleted, deleting and unknown sources are 404. */
export async function visibleSource(env: Env, id: string): Promise<RegistryRow> {
  const row = await env.CONTROL_DB.prepare(`SELECT ${REGISTRY_COLUMNS} FROM ${REGISTRY_FROM} WHERE r.id = ? AND r.state NOT IN ('deleting', 'deleted')`)
    .bind(id)
    .first<RegistryRow>();
  if (!row) throw notFound();
  return row;
}

export function archiveFor(env: Env, row: RegistryRow): D1Database | null {
  return row.binding_name ? resolvePartition(env, row.binding_name) : null;
}

/** Change-log insert for a source, reading its current registry row inside the same batch. */
export function sourceChange(db: D1Database, id: string, projection: object, now: number, guard?: { sql: string; params: unknown[] }): D1PreparedStatement {
  return db
    .prepare(`INSERT INTO changes(entity_type, entity_id, revision, deleted, projection_json, created_at) SELECT 'source', id, revision, 0, ?, ? FROM source_registry WHERE id = ?${guard ? ` AND ${guard.sql}` : ''}`)
    .bind(JSON.stringify(projection), now, id, ...(guard?.params ?? []));
}
