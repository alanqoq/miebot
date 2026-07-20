ALTER TABLE bot_plugins
    ADD COLUMN runtime_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN runtime_error TEXT,
    ADD CONSTRAINT ck_bot_plugins_runtime_state
        CHECK (runtime_state IN ('ACTIVE', 'PAUSED', 'QUARANTINED'));
UPDATE bot_plugins SET runtime_state = 'PAUSED' WHERE enabled = 0;
