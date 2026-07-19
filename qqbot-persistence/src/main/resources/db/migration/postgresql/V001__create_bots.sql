CREATE TABLE bots (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    display_name          VARCHAR(255) NOT NULL CHECK (length(btrim(display_name)) > 0),
    app_id                VARCHAR(255) NOT NULL CHECK (length(btrim(app_id)) > 0),
    environment           VARCHAR(16)  NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    app_secret_ciphertext TEXT         NOT NULL CHECK (length(btrim(app_secret_ciphertext)) > 0),
    app_secret_key_id     VARCHAR(255) NOT NULL CHECK (length(btrim(app_secret_key_id)) > 0),
    intents               BIGINT       NOT NULL DEFAULT 0 CHECK (intents >= 0),
    shard_index           INTEGER      NOT NULL DEFAULT 0 CHECK (shard_index >= 0),
    shard_count           INTEGER      NOT NULL DEFAULT 1 CHECK (shard_count > 0),
    enabled               SMALLINT     NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    revision              BIGINT       NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    CHECK (shard_index < shard_count),
    UNIQUE (environment, app_id)
);

CREATE INDEX ix_bots_enabled ON bots(enabled);
