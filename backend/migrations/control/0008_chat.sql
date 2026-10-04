-- Grounded chat turns (lld.md#chat, slice 12). The client turn ID is the turn's primary key and request identity.
ALTER TABLE chat_turns ADD COLUMN job_id TEXT;
ALTER TABLE chat_turns ADD COLUMN policy_revision INTEGER;
ALTER TABLE chat_turns ADD COLUMN reason TEXT;
ALTER TABLE chat_turns ADD COLUMN error_code TEXT;
-- Validated structured answer: claims with citations, evidence gaps and closed action proposals.
ALTER TABLE chat_turns ADD COLUMN output_json TEXT;
ALTER TABLE chat_turns ADD COLUMN completed_at INTEGER;
-- Set when a cited source is deleted: the stored answer text is removed, not shown.
ALTER TABLE chat_turns ADD COLUMN invalidated_at INTEGER;
CREATE INDEX chat_turns_thread ON chat_turns(thread_id, created_at);
-- The exact chunks an inference attempt was given, for tasks whose input is not a single source body.
ALTER TABLE inference_attempts ADD COLUMN chunk_ids_json TEXT;
