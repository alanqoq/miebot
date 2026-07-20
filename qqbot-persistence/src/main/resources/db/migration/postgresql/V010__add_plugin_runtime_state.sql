ALTER TABLE bot_plugins
    ADD COLUMN runtime_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (runtime_state IN ('ACTIVE', 'PAUSED', 'QUARANTINED')),
    ADD COLUMN runtime_error TEXT;
UPDATE bot_plugins SET runtime_state = 'PAUSED' WHERE enabled = 0;
