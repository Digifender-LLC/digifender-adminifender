-- Random marker written when a challenge is consumed, so the statements that follow in the same D1 batch can be
-- guarded on this exact consumption (a zero-row UPDATE does not abort a batch).
ALTER TABLE auth_challenges ADD COLUMN consumed_by TEXT;
-- Bounded per-source attempt counters for rate limits before expensive verification (lld.md#identity).
CREATE TABLE rate_limits(key TEXT NOT NULL, window_start INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(key, window_start));
-- Same guard markers for refresh-token rotation and recovery-secret use.
ALTER TABLE sessions ADD COLUMN consumed_by TEXT;
ALTER TABLE recovery_keys ADD COLUMN used_by TEXT;
