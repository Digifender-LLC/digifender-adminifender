-- The earlier prototype of device extraction (0011_device_extraction.sql, one lease row per attachment) was deployed to
-- staging; on 2026-10-09 the staging tables held no leases and no batches. Its two tables carry the names this design
-- reuses, so they are dropped first. Everything below replaces that prototype's lease and batch model.
DROP TABLE IF EXISTS extraction_batches;
DROP TABLE IF EXISTS extraction_leases;

-- Device extraction for PDFs and images (lld.md#text-extraction-and-ocr): one live lease per attachment, immutable batches
-- keyed by generation and ordinal, and the kind the server found when it deferred the file. Nothing here holds text: text
-- lives in the archive partition as unpublished chunks until a fenced control update points the attachment at the generation.
-- The kind is NULL for attachments deferred before this migration; those are not handed to a phone.
ALTER TABLE attachment_processing ADD COLUMN device_kind TEXT CHECK(device_kind IN ('pdf', 'image'));

CREATE TABLE extraction_leases(
  id TEXT PRIMARY KEY,
  attachment_id TEXT NOT NULL,
  source_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  lease_token TEXT NOT NULL,
  lease_until INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('leased', 'published', 'finished', 'released', 'expired', 'cancelled')),
  -- What the lease was granted against; any change to these makes later batches and the publication fail.
  processing_revision INTEGER NOT NULL,
  source_revision INTEGER NOT NULL,
  policy_revision INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  extractor_version TEXT NOT NULL,
  generation_id TEXT NOT NULL,
  ocr_allowed INTEGER NOT NULL CHECK(ocr_allowed IN (0, 1)),
  created_at INTEGER NOT NULL,
  closed_at INTEGER
);
CREATE UNIQUE INDEX extraction_leases_live ON extraction_leases(attachment_id) WHERE state = 'leased';
CREATE INDEX extraction_leases_device ON extraction_leases(device_id) WHERE state = 'leased';
CREATE INDEX extraction_leases_source ON extraction_leases(source_id);

CREATE TABLE extraction_batches(
  generation_id TEXT NOT NULL,
  ordinal INTEGER NOT NULL,
  attachment_id TEXT NOT NULL,
  source_id TEXT NOT NULL,
  batch_hash TEXT NOT NULL,
  -- Digest of the chunk rows this batch wrote, recomputed from the archive before anything is published.
  chunks_digest TEXT NOT NULL,
  chunk_count INTEGER NOT NULL,
  last_page INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(generation_id, ordinal)
) WITHOUT ROWID;
CREATE INDEX extraction_batches_source ON extraction_batches(source_id);
CREATE INDEX extraction_batches_attachment ON extraction_batches(attachment_id);
