CREATE TABLE bot_leases (
    bot_id                TEXT NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    shard_index           INTEGER NOT NULL CHECK (shard_index >= 0),
    owner_id              TEXT NOT NULL,
    lease_until           TEXT NOT NULL CHECK (lease_until GLOB '????-??-??T??:??:??*Z'),
    fencing_token         INTEGER NOT NULL CHECK (fencing_token > 0),
    updated_at            TEXT NOT NULL CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    PRIMARY KEY (bot_id, shard_index)
) STRICT;

CREATE INDEX ix_bot_leases_expiry ON bot_leases(lease_until);
