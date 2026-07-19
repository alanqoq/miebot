CREATE TABLE plugin_artifacts (
    plugin_id             VARCHAR(128) NOT NULL PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    version               VARCHAR(128) NOT NULL,
    api_compatibility     VARCHAR(128) NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    sha256                VARCHAR(128) NOT NULL,
    entrypoint            VARCHAR(255) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL
);

CREATE TABLE bot_plugins (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    plugin_id             VARCHAR(128) NOT NULL REFERENCES plugin_artifacts(plugin_id) ON DELETE RESTRICT,
    bot_id                VARCHAR(36) NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    config_json           TEXT NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    revision              BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    UNIQUE (plugin_id, bot_id)
);

CREATE INDEX ix_bot_plugins_bot ON bot_plugins(bot_id, enabled);
CREATE INDEX ix_bot_plugins_plugin ON bot_plugins(plugin_id, enabled);

CREATE TABLE plugin_deliveries (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id              VARCHAR(36) NOT NULL REFERENCES event_inbox(id) ON DELETE CASCADE,
    binding_id            VARCHAR(36) NOT NULL REFERENCES bot_plugins(id) ON DELETE CASCADE,
    handler_id            VARCHAR(128) NOT NULL,
    status                VARCHAR(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD_LETTER', 'PAUSED')),
    attempt               BIGINT NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          VARCHAR(40) NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    created_at            VARCHAR(40) NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    completed_at          VARCHAR(40),
    CHECK ((status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NULL)),
    UNIQUE (event_id, binding_id, handler_id)
);

CREATE INDEX ix_plugin_deliveries_available ON plugin_deliveries(status, available_at, created_at);
CREATE INDEX ix_plugin_deliveries_lease ON plugin_deliveries(status, lease_until);
