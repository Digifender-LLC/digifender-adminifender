-- "Read more pages" (lld.md#text-extraction-and-ocr): a document longer than one lease's 100 pages is published up to the page
-- that was read, and the owner can ask for the next pages. The request waits as `resume_page`; the next lease starts a new
-- generation that begins as a server-side copy of the published one, so what is searchable never changes until the new
-- generation is published whole.
ALTER TABLE attachment_processing ADD COLUMN pages_read INTEGER;
ALTER TABLE attachment_processing ADD COLUMN pages_total INTEGER;
ALTER TABLE attachment_processing ADD COLUMN resume_page INTEGER;
-- The first page this lease reads (1 for a first read) and the published generation a resumed lease copied from.
ALTER TABLE extraction_leases ADD COLUMN start_page INTEGER NOT NULL DEFAULT 1;
ALTER TABLE extraction_leases ADD COLUMN base_generation_id TEXT;
