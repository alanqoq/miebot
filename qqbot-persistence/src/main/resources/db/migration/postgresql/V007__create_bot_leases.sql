CREATE TABLE bot_leases (
    bot_id                VARCHAR(36) NOT NULL REFERENCES bots(id) ON DELETE CASCADE,
    shard_index           INTEGER NOT NULL CHECK (shard_index >= 0),
    owner_id              VARCHAR(255) NOT NULL,
    lease_until           VARCHAR(40) NOT NULL,
    fencing_token         BIGINT NOT NULL CHECK (fencing_token > 0),
    updated_at            VARCHAR(40) NOT NULL,
    PRIMARY KEY (bot_id, shard_index)
);

CREATE INDEX ix_bot_leases_expiry ON bot_leases(lease_until);
