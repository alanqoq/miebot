ALTER TABLE outbox_jobs ADD COLUMN producer_binding_id VARCHAR(36) REFERENCES bot_plugins(id) ON DELETE SET NULL;
ALTER TABLE outbox_jobs ADD COLUMN platform_message_id VARCHAR(255);
ALTER TABLE outbox_jobs ADD COLUMN platform_message_sequence INTEGER;
ALTER TABLE outbox_jobs ADD COLUMN platform_timestamp VARCHAR(128);

CREATE INDEX ix_outbox_producer_binding
    ON outbox_jobs(producer_binding_id, id);
