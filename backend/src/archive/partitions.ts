import type { Env } from '../env';

/**
 * Compiled deployment manifest of archive partition bindings (lld.md#storage). A registry row's binding_name
 * must resolve here; arbitrary or model-provided binding names never reach `env`.
 */
const PARTITION_BINDINGS = { ARCHIVE_0001: (env: Env) => env.ARCHIVE_0001 } as const;

export function resolvePartition(env: Env, bindingName: string): D1Database | null {
  return Object.hasOwn(PARTITION_BINDINGS, bindingName) ? PARTITION_BINDINGS[bindingName as keyof typeof PARTITION_BINDINGS](env) : null;
}

export async function writablePartition(env: Env): Promise<{ id: number; db: D1Database } | null> {
  const row = await env.CONTROL_DB.prepare("SELECT id, binding_name FROM archive_partitions WHERE state = 'writable'").first<{ id: number; binding_name: string }>();
  if (!row) return null;
  const db = resolvePartition(env, row.binding_name);
  return db ? { id: row.id, db } : null;
}
