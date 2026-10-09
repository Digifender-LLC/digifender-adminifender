/**
 * Release contract manifest (tdd.md#repository-organization-and-interfaces).
 * Bump a value only together with the schema or migration it names.
 */
export const RELEASE_CONTRACT = {
  httpSchemaVersion: 1,
  eventSchemaVersion: 1,
  controlMigration: 16,
  archiveMigration: 1,
} as const;

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function isUuidV4(value: unknown): value is string {
  return typeof value === 'string' && UUID_V4.test(value);
}
