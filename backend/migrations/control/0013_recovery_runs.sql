-- One row per recovery pass (ops/recovery-drill.md). The result is content-free: counts, hashes and opaque IDs.
CREATE TABLE recovery_runs(
  id TEXT PRIMARY KEY,
  state TEXT NOT NULL CHECK(state IN ('running', 'completed', 'blocked')),
  started_at INTEGER NOT NULL,
  finished_at INTEGER,
  result_json TEXT
);
