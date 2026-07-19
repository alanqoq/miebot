CREATE TABLE bot_leases (
    bot_id                VARCHAR(36) NOT NULL,
    shard_index           INTEGER NOT NULL,
    owner_id              VARCHAR(255) NOT NULL,
    lease_until           VARCHAR(40) NOT NULL,
    fencing_token         BIGINT NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    PRIMARY KEY (bot_id, shard_index),
    CONSTRAINT fk_bot_leases_bot FOREIGN KEY (bot_id) REFERENCES bots(id) ON DELETE CASCADE,
    CONSTRAINT ck_bot_leases_shard CHECK (shard_index >= 0),
    CONSTRAINT ck_bot_leases_token CHECK (fencing_token > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_bot_leases_expiry ON bot_leases(lease_until);
