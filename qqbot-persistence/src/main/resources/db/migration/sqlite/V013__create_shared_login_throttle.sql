CREATE TABLE admin_login_attempts (
    attempt_key TEXT NOT NULL PRIMARY KEY,
    failures INTEGER NOT NULL CHECK (failures >= 1),
    blocked_until TEXT,
    last_touched TEXT NOT NULL
) STRICT;
CREATE INDEX ix_admin_login_attempts_touched ON admin_login_attempts(last_touched);
