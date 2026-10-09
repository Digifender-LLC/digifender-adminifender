-- P2 signed Whumpo reports (lld.md#reports, slice P2a). Key material is stored encrypted under the REPORT_KEY_WRAP
-- Worker secret, apart from the reports it verifies; each key belongs to one service and one receiving alias.
ALTER TABLE report_keys ADD COLUMN alias TEXT;
ALTER TABLE report_keys ADD COLUMN secret_cipher BLOB;
ALTER TABLE report_keys ADD COLUMN created_at INTEGER;
CREATE TABLE service_reports(
  service_id TEXT NOT NULL REFERENCES service_catalog(id),
  event_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  source_id TEXT NOT NULL REFERENCES source_registry(id),
  key_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  concern_key TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  received_at INTEGER NOT NULL,
  report_json TEXT NOT NULL,
  PRIMARY KEY(service_id, event_id)
);
-- Latest trusted report per service, kind and concern key; only a higher sequence advances it.
CREATE TABLE service_report_heads(
  service_id TEXT NOT NULL,
  kind TEXT NOT NULL,
  concern_key TEXT NOT NULL,
  sequence INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  PRIMARY KEY(service_id, kind, concern_key)
);
-- One verification issue per source whose recognized report could not be trusted.
CREATE TABLE report_issues(source_id TEXT PRIMARY KEY REFERENCES source_registry(id), service_id TEXT, code TEXT NOT NULL, created_at INTEGER NOT NULL);
-- Heartbeat expectations (FR-044): when the expectation was set, and which concern currently tracks its absence.
ALTER TABLE heartbeat_expectations ADD COLUMN created_at INTEGER;
ALTER TABLE heartbeat_expectations ADD COLUMN concern_revision INTEGER;
-- Readiness reminders (FR-041): one concern per service, resource and dated obligation.
CREATE TABLE readiness_concerns(service_id TEXT NOT NULL, resource_ref TEXT NOT NULL, kind TEXT NOT NULL, due_at INTEGER NOT NULL, concern_id TEXT NOT NULL, PRIMARY KEY(service_id, resource_ref, kind, due_at));
