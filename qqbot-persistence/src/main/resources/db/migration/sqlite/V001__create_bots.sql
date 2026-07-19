CREATE TABLE bots (
    id                    TEXT    NOT NULL PRIMARY KEY,
    display_name          TEXT    NOT NULL CHECK (length(trim(display_name)) > 0),
    app_id                TEXT    NOT NULL CHECK (length(trim(app_id)) > 0),
    environment           TEXT    NOT NULL
        CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    app_secret_ciphertext TEXT    NOT NULL CHECK (length(trim(app_secret_ciphertext)) > 0),
    app_secret_key_id     TEXT    NOT NULL CHECK (length(trim(app_secret_key_id)) > 0),
    intents               INTEGER NOT NULL DEFAULT 0 CHECK (intents >= 0),
    shard_index           INTEGER NOT NULL DEFAULT 0 CHECK (shard_index >= 0),
    shard_count           INTEGER NOT NULL DEFAULT 1 CHECK (shard_count > 0),
    enabled               INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    revision              INTEGER NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_at            TEXT    NOT NULL
        CHECK (created_at GLOB '????-??-??T??:??:??*Z'),
    updated_at            TEXT    NOT NULL
        CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    CHECK (shard_index < shard_count),
    UNIQUE (environment, app_id)
) STRICT;

CREATE INDEX ix_bots_enabled ON bots(enabled);
