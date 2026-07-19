CREATE TABLE event_inbox (
    id                    TEXT    NOT NULL PRIMARY KEY,
    environment           TEXT    NOT NULL
        CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    bot_id                TEXT    NOT NULL REFERENCES bots(id) ON DELETE RESTRICT,
    event_type            TEXT    NOT NULL CHECK (length(trim(event_type)) > 0),
    platform_event_id     TEXT    NOT NULL CHECK (length(trim(platform_event_id)) > 0),
    payload               TEXT    NOT NULL CHECK (length(trim(payload)) > 0),
    status                TEXT    NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'DISPATCHED', 'DEAD_LETTER')),
    attempt               INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          TEXT    NOT NULL
        CHECK (available_at GLOB '????-??-??T??:??:??*Z'),
    lease_owner           TEXT,
    lease_until           TEXT
        CHECK (lease_until IS NULL OR lease_until GLOB '????-??-??T??:??:??*Z'),
    fencing_token         INTEGER NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    received_at           TEXT    NOT NULL
        CHECK (received_at GLOB '????-??-??T??:??:??*Z'),
    updated_at            TEXT    NOT NULL
        CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    CHECK (
        (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    UNIQUE (environment, bot_id, event_type, platform_event_id)
) STRICT;

CREATE INDEX ix_event_inbox_available
    ON event_inbox(status, available_at, received_at);
CREATE INDEX ix_event_inbox_expired_lease
    ON event_inbox(status, lease_until);

CREATE TABLE outbox_jobs (
    id                    TEXT    NOT NULL PRIMARY KEY,
    environment           TEXT    NOT NULL
        CHECK (environment IN ('SANDBOX', 'PRODUCTION')),
    bot_id                TEXT    NOT NULL REFERENCES bots(id) ON DELETE RESTRICT,
    source_event_id       TEXT    REFERENCES event_inbox(id) ON DELETE RESTRICT,
    job_type              TEXT    NOT NULL CHECK (length(trim(job_type)) > 0),
    dedup_key             TEXT,
    payload               TEXT    NOT NULL CHECK (length(trim(payload)) > 0),
    status                TEXT    NOT NULL DEFAULT 'PENDING'
        CHECK (status IN (
            'PENDING', 'IN_PROGRESS', 'RETRY_WAIT',
            'SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER'
        )),
    attempt               INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    available_at          TEXT    NOT NULL
        CHECK (available_at GLOB '????-??-??T??:??:??*Z'),
    lease_owner           TEXT,
    lease_until           TEXT
        CHECK (lease_until IS NULL OR lease_until GLOB '????-??-??T??:??:??*Z'),
    fencing_token         INTEGER NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    last_error            TEXT,
    created_at            TEXT    NOT NULL
        CHECK (created_at GLOB '????-??-??T??:??:??*Z'),
    updated_at            TEXT    NOT NULL
        CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    completed_at          TEXT
        CHECK (completed_at IS NULL OR completed_at GLOB '????-??-??T??:??:??*Z'),
    CHECK (
        (status = 'IN_PROGRESS' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'IN_PROGRESS' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CHECK (
        (status IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NOT NULL)
        OR
        (status NOT IN ('SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER') AND completed_at IS NULL)
    )
) STRICT;

CREATE UNIQUE INDEX uq_outbox_dedup_key
    ON outbox_jobs(environment, bot_id, dedup_key)
    WHERE dedup_key IS NOT NULL;
CREATE INDEX ix_outbox_available
    ON outbox_jobs(status, available_at, created_at);
CREATE INDEX ix_outbox_expired_lease
    ON outbox_jobs(status, lease_until);

CREATE TRIGGER trg_outbox_status_transition
BEFORE UPDATE OF status ON outbox_jobs
WHEN NOT (
    OLD.status = NEW.status
    OR (OLD.status IN ('PENDING', 'RETRY_WAIT') AND NEW.status = 'IN_PROGRESS')
    OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN (
        'IN_PROGRESS', 'RETRY_WAIT', 'SUCCEEDED', 'RESULT_UNKNOWN', 'DEAD_LETTER'
    ))
)
BEGIN
    SELECT RAISE(ABORT, 'invalid outbox status transition');
END;
