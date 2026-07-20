CREATE TABLE instance_plugin_hashes (
    instance_id TEXT NOT NULL,
    plugin_id TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    lease_until TEXT NOT NULL CHECK (lease_until GLOB '????-??-??T??:??:??*Z'),
    updated_at TEXT NOT NULL CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    PRIMARY KEY (instance_id, plugin_id)
) STRICT;
CREATE INDEX ix_instance_plugin_hashes_expiry ON instance_plugin_hashes(lease_until);
