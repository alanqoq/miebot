CREATE TABLE onebot11_configs (
    bot_id                   VARCHAR(36)   NOT NULL PRIMARY KEY,
    enabled                  TINYINT       NOT NULL DEFAULT 0,
    forward_enabled          TINYINT       NOT NULL DEFAULT 0,
    forward_bind_address     VARCHAR(255)  NOT NULL DEFAULT '127.0.0.1',
    forward_port             INT,
    reverse_enabled          TINYINT       NOT NULL DEFAULT 0,
    reverse_url              VARCHAR(2048),
    access_token_ciphertext  TEXT,
    access_token_key_id      VARCHAR(255),
    heartbeat_enabled        TINYINT       NOT NULL DEFAULT 1,
    heartbeat_interval_ms    INT           NOT NULL DEFAULT 15000,
    reconnect_interval_ms    INT           NOT NULL DEFAULT 3000,
    revision                 BIGINT        NOT NULL DEFAULT 1,
    created_at               VARCHAR(40)   NOT NULL,
    updated_at               VARCHAR(40)   NOT NULL,
    CONSTRAINT fk_onebot11_configs_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE,
    CONSTRAINT ck_onebot11_configs_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_onebot11_configs_forward CHECK (forward_enabled IN (0, 1)),
    CONSTRAINT ck_onebot11_configs_reverse CHECK (reverse_enabled IN (0, 1)),
    CONSTRAINT ck_onebot11_configs_heartbeat CHECK (heartbeat_enabled IN (0, 1)),
    CONSTRAINT ck_onebot11_configs_port CHECK (forward_port IS NULL OR forward_port BETWEEN 1 AND 65535),
    CONSTRAINT ck_onebot11_configs_intervals CHECK (
        heartbeat_interval_ms BETWEEN 1000 AND 300000
        AND reconnect_interval_ms BETWEEN 500 AND 300000),
    CONSTRAINT ck_onebot11_configs_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_onebot11_configs_forward_port ON onebot11_configs(forward_port);

CREATE TABLE onebot11_entity_ids (
    onebot_id   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id      VARCHAR(36)  NOT NULL,
    entity_type VARCHAR(16)  NOT NULL,
    scope_id    VARCHAR(512) NOT NULL DEFAULT '',
    raw_id      VARCHAR(512) NOT NULL,
    created_at  VARCHAR(40)  NOT NULL,
    CONSTRAINT fk_onebot11_entity_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE,
    CONSTRAINT uq_onebot11_entity UNIQUE (bot_id, entity_type, scope_id, raw_id),
    CONSTRAINT ck_onebot11_entity_type CHECK (entity_type IN ('SELF', 'USER', 'GROUP'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE onebot11_messages (
    message_id          INT          NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bot_id              VARCHAR(36)  NOT NULL,
    official_message_id VARCHAR(512) NOT NULL,
    target_type         VARCHAR(16)  NOT NULL,
    target_raw_id       VARCHAR(512) NOT NULL,
    direction           VARCHAR(16)  NOT NULL,
    message_type        VARCHAR(16)  NOT NULL,
    event_time          BIGINT       NOT NULL,
    user_id             BIGINT       NOT NULL,
    message_json        MEDIUMTEXT   NOT NULL,
    sender_json         MEDIUMTEXT   NOT NULL,
    created_at          VARCHAR(40)  NOT NULL,
    CONSTRAINT fk_onebot11_message_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE,
    CONSTRAINT uq_onebot11_message_official UNIQUE (bot_id, official_message_id),
    CONSTRAINT ck_onebot11_message_target CHECK (target_type IN ('C2C', 'GROUP')),
    CONSTRAINT ck_onebot11_message_direction CHECK (direction IN ('INCOMING', 'OUTGOING')),
    CONSTRAINT ck_onebot11_message_type CHECK (message_type IN ('private', 'group'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_onebot11_messages_bot_created ON onebot11_messages(bot_id, created_at);
