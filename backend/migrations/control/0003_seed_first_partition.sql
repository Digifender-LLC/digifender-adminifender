-- P1 begins with ARCHIVE_0001 as the writable partition (lld.md#storage). Later partitions are registered
-- by the reviewed rollover procedure (tdd.md#storage-partitions-and-retention), never by a migration default.
INSERT INTO archive_partitions(id, binding_name, state, created_at) VALUES (1, 'ARCHIVE_0001', 'writable', 0);
