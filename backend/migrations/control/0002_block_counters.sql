-- Aggregate block-rule rejections (lld.md#ingestion step 2): counts per rule and UTC day only.
-- No body, subject or address is stored for blocked mail.
CREATE TABLE block_counters(rule_id TEXT NOT NULL REFERENCES block_rules(id), day TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(rule_id, day));
