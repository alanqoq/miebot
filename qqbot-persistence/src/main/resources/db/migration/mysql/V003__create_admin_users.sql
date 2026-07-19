CREATE TABLE admin_users (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(16)  NOT NULL DEFAULT 'ADMIN',
    enabled               TINYINT      NOT NULL DEFAULT 1,
    singleton_key         TINYINT      NOT NULL DEFAULT 1,
    created_at            VARCHAR(40)  NOT NULL,
    updated_at            VARCHAR(40)  NOT NULL,
    CONSTRAINT ck_admin_username CHECK (CHAR_LENGTH(TRIM(username)) BETWEEN 3 AND 64),
    CONSTRAINT ck_admin_role CHECK (role = 'ADMIN'),
    CONSTRAINT ck_admin_enabled CHECK (enabled IN (0, 1)),
    CONSTRAINT ck_admin_singleton CHECK (singleton_key = 1),
    CONSTRAINT uq_admin_username UNIQUE (username),
    CONSTRAINT uq_admin_singleton UNIQUE (singleton_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
