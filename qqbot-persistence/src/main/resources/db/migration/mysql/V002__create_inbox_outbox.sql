CREATE TABLE event_inbox (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    environment           VARCHAR(16)  NOT NULL,
    bot_id                VARCHAR(36)  NOT NULL,
    event_type            VARCHAR(128) NOT NULL,
    platform_event_id     VARCHAR(255) NOT NULL,
    payload               LONGTEXT     NOT NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED',
    attempt               BIGINT       NOT NULL DEFAULT 0,
    available_at          VARCHAR(40)  NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT       NOT NULL DEFAULT 0,
    last_error            TEXT,
    received_at           VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    CONSTRAINT fk_event_inbox_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE RESTRICT,
    CONSTRAINT ck_event_inbox_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_event_inbox_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'DISPATCHED', 'DEAD_LETTER')),
    CONSTRAINT ck_event_inbox_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_event_inbox_fencing CHECK (fencing_token >= 0),
    CONSTRAINT ck_event_inbox_lease CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT uq_event_inbox_platform UNIQUE (environment, bot_id, event_type, platform_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_event_inbox_available ON event_inbox(status, available_at, received_at);
CREATE INDEX ix_event_inbox_expired_lease ON event_inbox(status, lease_until);

CREATE TABLE outbox_jobs (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    environment           VARCHAR(16)  NOT NULL,
    bot_id                VARCHAR(36)  NOT NULL,
    source_event_id       VARCHAR(36),
    job_type              VARCHAR(128) NOT NULL,
    dedup_key             VARCHAR(512),
    payload               LONGTEXT     NOT NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    attempt               BIGINT       NOT NULL DEFAULT 0,
    available_at          VARCHAR(40)  NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT       NOT NULL DEFAULT 0,
    last_error            TEXT,
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    completed_at          VARCHAR(40),
    CONSTRAINT fk_outbox_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbox_event FOREIGN KEY (source_event_id) REFERENCES event_inbox(id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbox_environment CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    CONSTRAINT ck_outbox_status CHECK (status IN (
        'PENDING', 'IN_PROGRESS', 'RETRY_WAIT', 'SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER'
    )),
    CONSTRAINT ck_outbox_attempt CHECK (attempt >= 0),
    CONSTRAINT ck_outbox_fencing CHECK (fencing_token >= 0),
    CONSTRAINT ck_outbox_lease CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_outbox_completed CHECK (
        (status IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE UNIQUE INDEX uq_outbox_dedup_key ON outbox_jobs(environment, bot_id, dedup_key);
CREATE INDEX ix_outbox_available ON outbox_jobs(status, available_at, created_at);
CREATE INDEX ix_outbox_expired_lease ON outbox_jobs(status, lease_until);
