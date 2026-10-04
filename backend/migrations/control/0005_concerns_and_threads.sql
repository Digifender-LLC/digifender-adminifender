-- Verified thread references for concern grouping (lld.md#concerns): message IDs scoped to the recipient mailbox.
CREATE TABLE source_threads(source_id TEXT PRIMARY KEY REFERENCES source_registry(id), mailbox_hash TEXT NOT NULL, message_id TEXT, references_json TEXT NOT NULL);
CREATE INDEX source_threads_message ON source_threads(mailbox_hash, message_id);
-- Waiting state keeps the awaited party separately from the note (lld.md#concerns).
ALTER TABLE concerns ADD COLUMN awaited_party TEXT;
CREATE INDEX concerns_status ON concerns(status, updated_at);
CREATE INDEX concern_sources_source ON concern_sources(source_id);
-- Always empty. A batch statement inserts NULL here when its precondition is false, which aborts the whole D1
-- batch; a zero-row UPDATE alone would not (lld.md#jobs).
CREATE TABLE batch_assertions(ok INTEGER NOT NULL);
