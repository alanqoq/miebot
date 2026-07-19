CREATE TABLE plugin_storage (
    binding_id            VARCHAR(36) NOT NULL REFERENCES bot_plugins(id) ON DELETE CASCADE,
    namespace             VARCHAR(64) NOT NULL,
    storage_key           VARCHAR(128) NOT NULL,
    value_text            TEXT NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    PRIMARY KEY (binding_id, namespace, storage_key)
);

CREATE INDEX ix_plugin_storage_namespace ON plugin_storage(binding_id, namespace);
