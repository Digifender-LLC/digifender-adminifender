-- Hybrid inference routing, budgets and device work (lld.md#inference, slice 11).
-- Attempts gain the routing decision, task and lease columns; one succeeded attempt per job is authoritative.
ALTER TABLE inference_attempts ADD COLUMN task TEXT NOT NULL DEFAULT 'triage';
ALTER TABLE inference_attempts ADD COLUMN usage_kind TEXT NOT NULL DEFAULT 'automatic' CHECK(usage_kind IN ('automatic','interactive'));
ALTER TABLE inference_attempts ADD COLUMN source_id TEXT;
ALTER TABLE inference_attempts ADD COLUMN source_revision INTEGER;
ALTER TABLE inference_attempts ADD COLUMN reason TEXT NOT NULL DEFAULT '';
ALTER TABLE inference_attempts ADD COLUMN repair INTEGER NOT NULL DEFAULT 0;
ALTER TABLE inference_attempts ADD COLUMN device_id TEXT;
ALTER TABLE inference_attempts ADD COLUMN lease_token TEXT;
ALTER TABLE inference_attempts ADD COLUMN lease_until INTEGER;
ALTER TABLE inference_attempts ADD COLUMN error_code TEXT;
ALTER TABLE inference_attempts ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0;
CREATE INDEX inference_attempts_job ON inference_attempts(job_id, created_at);
CREATE INDEX inference_attempts_source ON inference_attempts(source_id, created_at);
CREATE INDEX inference_attempts_queue ON inference_attempts(route, state, created_at);
CREATE UNIQUE INDEX inference_attempts_one_success ON inference_attempts(job_id) WHERE state = 'succeeded';

-- Latest OS capability report per device; routing uses it only while fresh.
CREATE TABLE device_readiness(device_id TEXT PRIMARY KEY REFERENCES devices(id), reported_at INTEGER NOT NULL, foreground INTEGER NOT NULL, charging INTEGER NOT NULL, battery_percent INTEGER NOT NULL, thermal TEXT NOT NULL, model_id TEXT, manifest_hash TEXT);
