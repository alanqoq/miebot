CREATE TABLE instance_plugin_hashes (
    instance_id VARCHAR(255) NOT NULL,
    plugin_id VARCHAR(128) NOT NULL,
    sha256 VARCHAR(128) NOT NULL,
    lease_until VARCHAR(40) NOT NULL,
    updated_at VARCHAR(40) NOT NULL,
    PRIMARY KEY (instance_id, plugin_id)
);
CREATE INDEX ix_instance_plugin_hashes_expiry ON instance_plugin_hashes(lease_until);
