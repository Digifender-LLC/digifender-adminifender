/**
 * A batch statement that aborts the whole D1 batch when `condition` is false, by inserting NULL into the
 * NOT NULL column of the always-empty batch_assertions table. Use it before dependent writes whose
 * precondition (for example an expected revision) a zero-row UPDATE would otherwise skip silently.
 */
export function assertion(db: D1Database, condition: string, ...params: unknown[]): D1PreparedStatement {
  return db.prepare(`INSERT INTO batch_assertions(ok) SELECT NULL WHERE NOT (${condition})`).bind(...params);
}

export function isAssertionFailure(error: unknown): boolean {
  return error instanceof Error && /NOT NULL constraint failed: batch_assertions\.ok/.test(error.message);
}
