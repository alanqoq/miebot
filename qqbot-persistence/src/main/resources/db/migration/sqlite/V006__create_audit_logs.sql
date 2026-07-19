CREATE TABLE audit_logs (
    id                    TEXT NOT NULL PRIMARY KEY,
    actor_username        TEXT,
    action                TEXT NOT NULL,
    resource_path         TEXT NOT NULL,
    outcome_status        INTEGER NOT NULL CHECK (outcome_status BETWEEN 100 AND 599),
    remote_address        TEXT,
    trace_id              TEXT,
    created_at            TEXT NOT NULL
        CHECK (created_at GLOB '????-??-??T??:??:??*Z')
) STRICT;

CREATE INDEX ix_audit_logs_created ON audit_logs(created_at DESC, id DESC);
CREATE INDEX ix_audit_logs_actor ON audit_logs(actor_username, created_at DESC);
