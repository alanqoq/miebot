CREATE TABLE admin_users (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL CHECK (length(btrim(username)) BETWEEN 3 AND 64),
    password_hash         VARCHAR(255) NOT NULL CHECK (length(btrim(password_hash)) > 0),
    role                  VARCHAR(16)  NOT NULL DEFAULT 'ADMIN' CHECK (role = 'ADMIN'),
    enabled               SMALLINT     NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    singleton_key         SMALLINT     NOT NULL DEFAULT 1 UNIQUE CHECK (singleton_key = 1),
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL
);

CREATE UNIQUE INDEX uq_admin_username_ci ON admin_users ((lower(username)));
