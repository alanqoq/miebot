CREATE TABLE plugin_artifacts (
    plugin_id             VARCHAR(128) NOT NULL PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    version               VARCHAR(128) NOT NULL,
    api_compatibility     VARCHAR(128) NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    sha256                VARCHAR(128) NOT NULL,
    entrypoint            VARCHAR(255) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1,
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    CONSTRAINT ck_plugin_artifact_enabled CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE bot_plugins (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    plugin_id             VARCHAR(128) NOT NULL,
    bot_id                VARCHAR(36) NOT NULL,
    config_json           LONGTEXT NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1,
    revision              BIGINT NOT NULL DEFAULT 0,
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    CONSTRAINT fk_bot_plugins_plugin FOREIGN KEY (plugin_id) REFERENCES plugin_artifacts(plugin_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bot_plugins_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE,
    CONSTRAINT ck_bot_plugins_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_bot_plugins_revision CHECK (revision >= 0),
    CONSTRAINT uq_bot_plugins_pair UNIQUE (plugin_id, bot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_bot_plugins_bot ON bot_plugins(bot_id, enabled);
CREATE INDEX ix_bot_plugins_plugin ON bot_plugins(plugin_id, enabled);

CREATE TABLE plugin_deliveries (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id              VARCHAR(36) NOT NULL,
    binding_id            VARCHAR(36) NOT NULL,
    handler_id            VARCHAR(128) NOT NULL,
    status                VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt               BIGINT NOT NULL DEFAULT 0,
    available_at          VARCHAR(40) NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT NOT NULL DEFAULT 0,
    last_error            TEXT,
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    completed_at          VARCHAR(40),
    CONSTRAINT fk_plugin_deliveries_event FOREIGN KEY (event_id) REFERENCES event_inbox(id) ON DELETE CASCADE,
    CONSTRAINT fk_plugin_deliveries_binding FOREIGN KEY (binding_id) REFERENCES bot_plugins(id) ON DELETE CASCADE,
    CONSTRAINT ck_plugin_deliveries_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD_LETTER', 'PAUSED')),
    CONSTRAINT ck_plugin_deliveries_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_plugin_deliveries_fencing CHECK (fencing_token >= 0),
    CONSTRAINT ck_plugin_deliveries_lease CHECK ((status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)),
    CONSTRAINT ck_plugin_deliveries_completed CHECK ((status IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NOT NULL) OR (status NOT IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NULL)),
    CONSTRAINT uq_plugin_deliveries_key UNIQUE (event_id, binding_id, handler_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_plugin_deliveries_available ON plugin_deliveries(status, available_at, created_at);
CREATE INDEX ix_plugin_deliveries_lease ON plugin_deliveries(status, lease_until);
