-- Health observations (lld.md#health, slice 13): last successful scheduled pass and per-device sync time.
CREATE TABLE observations(name TEXT PRIMARY KEY, observed_at INTEGER NOT NULL);
ALTER TABLE devices ADD COLUMN last_sync_at INTEGER;
