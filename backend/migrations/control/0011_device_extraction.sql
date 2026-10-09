-- Device PDF/OCR extraction (lld.md#text-extraction-and-ocr, slice 14g): one lease and page checkpoint per attachment,
-- and the immutable identity of every batch a phone has written.
CREATE TABLE extraction_leases(
  attachment_id TEXT PRIMARY KEY REFERENCES attachment_processing(attachment_id),
  generation_id TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  policy_revision INTEGER NOT NULL,
  lease_token TEXT,
  device_id TEXT,
  lease_until INTEGER,
  next_batch INTEGER NOT NULL DEFAULT 0,
  next_page INTEGER NOT NULL DEFAULT 1,
  job_pages INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE extraction_batches(
  attachment_id TEXT NOT NULL,
  generation_id TEXT NOT NULL,
  batch_ordinal INTEGER NOT NULL,
  batch_hash TEXT NOT NULL,
  first_page INTEGER NOT NULL,
  last_page INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(attachment_id, generation_id, batch_ordinal)
);
