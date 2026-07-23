CREATE TABLE onebot11_configs (
    bot_id                   VARCHAR(36)   NOT NULL PRIMARY KEY
        REFERENCES bots(id) ON DELETE CASCADE,
    enabled                  SMALLINT      NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
    forward_enabled          SMALLINT      NOT NULL DEFAULT 0 CHECK (forward_enabled IN (0, 1)),
    forward_bind_address     VARCHAR(255)  NOT NULL DEFAULT '127.0.0.1',
    forward_port             INTEGER CHECK (forward_port BETWEEN 1 AND 65535),
    reverse_enabled          SMALLINT      NOT NULL DEFAULT 0 CHECK (reverse_enabled IN (0, 1)),
    reverse_url              VARCHAR(2048),
    access_token_ciphertext  TEXT,
    access_token_key_id      VARCHAR(255),
    heartbeat_enabled        SMALLINT      NOT NULL DEFAULT 1 CHECK (heartbeat_enabled IN (0, 1)),
    heartbeat_interval_ms    INTEGER       NOT NULL DEFAULT 15000
        CHECK (heartbeat_interval_ms BETWEEN 1000 AND 300000),
    reconnect_interval_ms    INTEGER       NOT NULL DEFAULT 3000
        CHECK (reconnect_interval_ms BETWEEN 500 AND 300000),
    revision                 BIGINT        NOT NULL DEFAULT 1 CHECK (revision >= 1),
    created_at               VARCHAR(40)   NOT NULL,
    updated_at               VARCHAR(40)   NOT NULL,
    CHECK ((access_token_ciphertext IS NULL) = (access_token_key_id IS NULL))
);

CREATE INDEX ix_onebot11_configs_forward_port ON onebot11_configs(forward_port);

CREATE TABLE onebot11_entity_ids (
    onebot_id   BIGSERIAL    PRIMARY KEY,
    bot_id      VARCHAR(36)  NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    entity_type VARCHAR(16)  NOT NULL CHECK (entity_type IN ('SELF', 'USER', 'GROUP')),
    scope_id    VARCHAR(512) NOT NULL DEFAULT '',
    raw_id      VARCHAR(512) NOT NULL,
    created_at  VARCHAR(40)  NOT NULL,
    UNIQUE (bot_id, entity_type, scope_id, raw_id)
);

CREATE TABLE onebot11_messages (
    message_id          SERIAL       PRIMARY KEY CHECK (message_id BETWEEN 1 AND 2147483647),
    bot_id              VARCHAR(36)  NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    official_message_id VARCHAR(512) NOT NULL,
    target_type         VARCHAR(16)  NOT NULL CHECK (target_type IN ('C2C', 'GROUP')),
    target_raw_id       VARCHAR(512) NOT NULL,
    direction           VARCHAR(16)  NOT NULL CHECK (direction IN ('INCOMING', 'OUTGOING')),
    message_type        VARCHAR(16)  NOT NULL CHECK (message_type IN ('private', 'group')),
    event_time          BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    message_json        TEXT         NOT NULL,
    sender_json         TEXT         NOT NULL,
    created_at          VARCHAR(40)  NOT NULL,
    UNIQUE (bot_id, official_message_id)
);

CREATE INDEX ix_onebot11_messages_bot_created ON onebot11_messages(bot_id, created_at);
