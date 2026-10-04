-- Sync bootstrap snapshots and change retention (lld.md#sync).
CREATE INDEX changes_entity ON changes(entity_type, entity_id, seq);
-- A live snapshot pins the projections it pages; retention never prunes a version one still needs.
CREATE TABLE sync_snapshots(id TEXT PRIMARY KEY, seq INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
-- Highest pruned tombstone sequence; a change cursor before it must rebootstrap.
CREATE TABLE change_floor(id TEXT PRIMARY KEY CHECK(id='floor'), seq INTEGER NOT NULL);
