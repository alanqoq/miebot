CREATE TABLE audit_logs (
    id                    VARCHAR(36) NOT NULL PRIMARY KEY,
    actor_username        VARCHAR(64),
    action                VARCHAR(16) NOT NULL,
    resource_path         VARCHAR(512) NOT NULL,
    outcome_status        INTEGER NOT NULL CHECK (outcome_status BETWEEN 100 AND 599),
    remote_address        VARCHAR(128),
    trace_id              VARCHAR(128),
    created_at            VARCHAR(40) NOT NULL
);

CREATE INDEX ix_audit_logs_created ON audit_logs(created_at DESC, id DESC);
CREATE INDEX ix_audit_logs_actor ON audit_logs(actor_username, created_at DESC);
