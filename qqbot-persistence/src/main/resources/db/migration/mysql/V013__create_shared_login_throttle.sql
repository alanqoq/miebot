CREATE TABLE admin_login_attempts (
    attempt_key CHAR(64) NOT NULL PRIMARY KEY,
    failures BIGINT NOT NULL,
    blocked_until VARCHAR(40),
    last_touched VARCHAR(40) NOT NULL
) ENGINE=InnoDB;
CREATE INDEX ix_admin_login_attempts_touched ON admin_login_attempts(last_touched);
