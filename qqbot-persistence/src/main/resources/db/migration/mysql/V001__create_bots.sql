CREATE TABLE bots (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    display_name          VARCHAR(255) NOT NULL,
    app_id                VARCHAR(255) NOT NULL,
    environment           VARCHAR(16)  NOT NULL,
    app_secret_ciphertext TEXT         NOT NULL,
    app_secret_key_id     VARCHAR(255) NOT NULL,
    intents               BIGINT       NOT NULL DEFAULT 0,
    shard_index           INT          NOT NULL DEFAULT 0,
    shard_count           INT          NOT NULL DEFAULT 1,
    enabled               TINYINT      NOT NULL DEFAULT 0,
    revision              BIGINT       NOT NULL DEFAULT 1,
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    CONSTRAINT ck_bots_display_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_bots_app_id CHECK (CHAR_LENGTH(TRIM(app_id)) > 0),
    CONSTRAINT ck_bots_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_bots_intents CHECK (intents >= 0),
    CONSTRAINT ck_bots_shards CHECK (shard_index >= 0 AND shard_count > 0 AND shard_index < shard_count),
    CONSTRAINT ck_bots_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_bots_revision CHECK (revision >= 1),
    CONSTRAINT uq_bots_environment_app UNIQUE (environment, app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_bots_enabled ON bots(enabled);
