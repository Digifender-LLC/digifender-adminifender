-- Device text/OCR transport. Source text is staged in source-scoped R2, never in CONTROL.
CREATE TABLE device_extraction_jobs(
  id TEXT PRIMARY KEY,
  attachment_id TEXT NOT NULL UNIQUE REFERENCES attachment_processing(attachment_id) ON DELETE CASCADE,
  source_id TEXT NOT NULL REFERENCES source_registry(id),
  kind TEXT NOT NULL CHECK(kind IN ('pdf','image')),
  content_hash TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  extractor_version TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('queued','leased','deferred','complete')),
  source_revision INTEGER NOT NULL,
  policy_revision INTEGER NOT NULL,
  processing_revision INTEGER NOT NULL DEFAULT 0,
  device_id TEXT REFERENCES devices(id),
  lease_token TEXT,
  lease_until INTEGER,
  generation_id TEXT,
  pdf_text INTEGER NOT NULL DEFAULT 0,
  latin_ocr INTEGER NOT NULL DEFAULT 0,
  next_batch INTEGER NOT NULL DEFAULT 0,
  next_page INTEGER NOT NULL DEFAULT 0,
  next_part INTEGER NOT NULL DEFAULT 0,
  next_offset INTEGER NOT NULL DEFAULT 0,
  text_bytes INTEGER NOT NULL DEFAULT 0,
  window_start_page INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX device_extraction_due ON device_extraction_jobs(state,lease_until,created_at);
CREATE TABLE device_extraction_batches(
  job_id TEXT NOT NULL REFERENCES device_extraction_jobs(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  batch_hash TEXT NOT NULL,
  object_key TEXT NOT NULL,
  receipt_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(job_id,ordinal)
);
