CREATE TABLE plugin_artifacts (
    plugin_id             TEXT    NOT NULL PRIMARY KEY,
    name                  TEXT    NOT NULL,
    version               TEXT    NOT NULL,
    api_compatibility     TEXT    NOT NULL,
    file_name             TEXT    NOT NULL,
    sha256                TEXT    NOT NULL,
    entrypoint            TEXT    NOT NULL,
    status                TEXT    NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL
) STRICT;

CREATE TABLE bot_plugins (
    id                    TEXT    NOT NULL PRIMARY KEY,
    plugin_id             TEXT    NOT NULL REFERENCES plugin_artifacts(plugin_id) ON DELETE RESTRICT,
    bot_id                TEXT    NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    config_json           TEXT    NOT NULL,
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    revision              INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL,
    UNIQUE (plugin_id, bot_id)
) STRICT;

CREATE INDEX ix_bot_plugins_bot ON bot_plugins(bot_id, enabled);
CREATE INDEX ix_bot_plugins_plugin ON bot_plugins(plugin_id, enabled);

CREATE TABLE plugin_deliveries (
    id                    TEXT    NOT NULL PRIMARY KEY,
    event_id              TEXT    NOT NULL REFERENCES event_inbox(id) ON DELETE CASCADE,
    binding_id            TEXT    NOT NULL REFERENCES bot_plugins(id) ON DELETE CASCADE,
    handler_id            TEXT    NOT NULL,
    status                TEXT    NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'RETRY_WAIT', 'SUCCEEDED', 'DEAD_LETTER', 'PAUSED')),
    attempt               INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          TEXT    NOT NULL,
    lease_owner           TEXT,
    lease_until           TEXT,
    fencing_token         INTEGER NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL,
    completed_at          TEXT,
    CHECK ((status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)),
    CHECK ((status IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'DEAD_LETTER') AND completed_at IS NULL)),
    UNIQUE (event_id, binding_id, handler_id)
) STRICT;

CREATE INDEX ix_plugin_deliveries_available
    ON plugin_deliveries(status, available_at, created_at);
CREATE INDEX ix_plugin_deliveries_lease
    ON plugin_deliveries(status, lease_until);
