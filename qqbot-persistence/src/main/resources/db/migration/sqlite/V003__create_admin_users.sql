CREATE TABLE admin_users (
    id                    TEXT    NOT NULL PRIMARY KEY,
    username              TEXT    NOT NULL COLLATE NOCASE
        CHECK (length(trim(username)) BETWEEN 3 AND 64),
    password_hash         TEXT    NOT NULL CHECK (length(trim(password_hash)) > 0),
    role                  TEXT    NOT NULL DEFAULT 'ADMIN' CHECK (role = 'ADMIN'),
    enabled               INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
    singleton_key         INTEGER NOT NULL DEFAULT 1 UNIQUE CHECK (singleton_key = 1),
    created_at            TEXT    NOT NULL
        CHECK (created_at GLOB '????-??-??T??:??:??*Z'),
    updated_at            TEXT    NOT NULL
        CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    UNIQUE (username)
) STRICT;
