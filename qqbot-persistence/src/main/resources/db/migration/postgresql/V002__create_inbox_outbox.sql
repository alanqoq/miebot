CREATE TABLE event_inbox (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    environment           VARCHAR(16)  NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    bot_id                VARCHAR(36)  NOT NULL REFERENCES bots(id) ON DELETE RESTRICT,
    event_type            VARCHAR(128) NOT NULL CHECK (length(btrim(event_type)) > 0),
    platform_event_id     VARCHAR(255) NOT NULL CHECK (length(btrim(platform_event_id)) > 0),
    payload               TEXT         NOT NULL CHECK (length(payload) > 0),
    status                VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'DISPATCHED', 'DEAD_LETTER')),
    attempt               BIGINT       NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          VARCHAR(40)  NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT       NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    received_at           VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    UNIQUE (environment, bot_id, event_type, platform_event_id)
);

CREATE INDEX ix_event_inbox_available ON event_inbox(status, available_at, received_at);
CREATE INDEX ix_event_inbox_expired_lease ON event_inbox(status, lease_until);

CREATE TABLE outbox_jobs (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    environment           VARCHAR(16)  NOT NULL CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    bot_id                VARCHAR(36)  NOT NULL REFERENCES bots(id) ON DELETE RESTRICT,
    source_event_id       VARCHAR(36)  REFERENCES event_inbox(id) ON DELETE RESTRICT,
    job_type              VARCHAR(128) NOT NULL CHECK (length(btrim(job_type)) > 0),
    dedup_key             VARCHAR(512),
    payload               TEXT         NOT NULL CHECK (length(payload) > 0),
    status                VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN (
            'PENDING', 'IN_PROGRESS', 'RETRY_WAIT',
            'SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER'
        )),
    attempt               BIGINT       NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          VARCHAR(40)  NOT NULL,
    lease_owner           VARCHAR(255),
    lease_until           VARCHAR(40),
    fencing_token         BIGINT       NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    completed_at          VARCHAR(40),
    CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CHECK (
        (status IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX uq_outbox_dedup_key
    ON outbox_jobs(environment, bot_id, dedup_key)
    WHERE dedup_key IS NOT NULL;
CREATE INDEX ix_outbox_available ON outbox_jobs(status, available_at, created_at);
CREATE INDEX ix_outbox_expired_lease ON outbox_jobs(status, lease_until);
