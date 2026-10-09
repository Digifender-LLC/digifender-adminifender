-- Export scope, membership and invalidation reasons (lld.md#exports), and operation-bound deletion ledger lookups.
-- Exports created before this migration have no scope. They stay NULL and are treated as unknown: never built, never
-- downloadable, and their objects are removed by the scheduled cleanup, so no earlier export is read as a broader one.
ALTER TABLE exports ADD COLUMN scope_json TEXT;
ALTER TABLE exports ADD COLUMN invalidated_reason TEXT;
-- Each build attempt writes under exports/{id}/{attempt}/ (the attempt is the job's lease token). `attempt` names the one
-- attempt whose ZIP, manifest and membership were published; an attempt that lost its lease can publish nothing.
ALTER TABLE exports ADD COLUMN attempt TEXT;
-- Cleanup bookkeeping: when the export stopped changing (ready or finished otherwise) and when the scheduled pass next
-- visits it (a failed removal is retried sooner).
ALTER TABLE exports ADD COLUMN settled_at INTEGER;
ALTER TABLE exports ADD COLUMN cleanup_next_at INTEGER;
-- The sources an attempt actually contains, written before it can become ready. Only the published attempt's rows count:
-- deletion and the download check read them through exports.attempt.
CREATE TABLE export_sources(
  export_id TEXT NOT NULL REFERENCES exports(id),
  attempt TEXT NOT NULL,
  source_id TEXT NOT NULL REFERENCES source_registry(id),
  PRIMARY KEY(export_id, attempt, source_id)
) WITHOUT ROWID;
CREATE INDEX export_sources_source ON export_sources(source_id);
-- Multipart uploads an attempt has started and not yet completed or aborted. The parts of an unfinished upload are not
-- listed as objects by R2, so the prefix sweep cannot see them; the upload ID is recorded right after the upload is
-- created, and the sweep aborts what is still recorded for an attempt that did not publish.
CREATE TABLE export_uploads(
  export_id TEXT NOT NULL REFERENCES exports(id),
  attempt TEXT NOT NULL,
  upload_id TEXT NOT NULL,
  object_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(export_id, attempt, upload_id)
) WITHOUT ROWID;
CREATE INDEX exports_cleanup ON exports(cleanup_next_at);
-- GET /operations/{id} reads cleanup progress by the operation that deleted the sources.
CREATE INDEX deletion_ledger_operation ON deletion_ledger(json_extract(targets_json, '$.operationId'));
