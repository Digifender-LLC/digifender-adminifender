-- Notification events, deliveries and push registration (lld.md#notifications, slice 13).
ALTER TABLE notification_events ADD COLUMN signal_id TEXT;
ALTER TABLE notification_events ADD COLUMN due_revision INTEGER;
ALTER TABLE notification_events ADD COLUMN job_id TEXT;
ALTER TABLE notification_events ADD COLUMN reason TEXT;
ALTER TABLE notification_events ADD COLUMN settled_at INTEGER;
-- One push per material critical signal, even when a merge moves the signal to another concern.
CREATE UNIQUE INDEX notification_critical_signal ON notification_events(signal_id) WHERE kind = 'critical';
CREATE INDEX notification_events_recent ON notification_events(kind, created_at);
CREATE INDEX notification_events_state ON notification_events(state, created_at);
-- Per-device delivery: dispatching is written before the provider call, so an interrupted send is uncertain.
ALTER TABLE notification_deliveries ADD COLUMN error_code TEXT;
ALTER TABLE notification_deliveries ADD COLUMN dispatched_at INTEGER;
-- Push registration through the device's own session; the token itself stays encrypted in fcm_token_cipher.
ALTER TABLE devices ADD COLUMN push_permission TEXT CHECK(push_permission IN ('granted','denied'));
ALTER TABLE devices ADD COLUMN push_app_version TEXT;
ALTER TABLE devices ADD COLUMN push_updated_at INTEGER;
ALTER TABLE devices ADD COLUMN push_invalid_at INTEGER;
