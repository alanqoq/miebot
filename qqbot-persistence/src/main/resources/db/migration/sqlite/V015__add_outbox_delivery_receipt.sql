ALTER TABLE outbox_jobs ADD COLUMN producer_binding_id TEXT REFERENCES bot_plugins(id) ON DELETE SET NULL;
ALTER TABLE outbox_jobs ADD COLUMN platform_message_id TEXT;
ALTER TABLE outbox_jobs ADD COLUMN platform_message_sequence INTEGER;
ALTER TABLE outbox_jobs ADD COLUMN platform_timestamp TEXT;

CREATE INDEX ix_outbox_producer_binding
    ON outbox_jobs(producer_binding_id, id);
