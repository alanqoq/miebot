CREATE TABLE plugin_storage (
    binding_id            TEXT NOT NULL REFERENCES bot_plugins(id) ON DELETE CASCADE,
    namespace             TEXT NOT NULL CHECK (length(namespace) BETWEEN 1 AND 64),
    storage_key           TEXT NOT NULL CHECK (length(storage_key) BETWEEN 1 AND 128),
    value_text            TEXT NOT NULL,
    updated_at            TEXT NOT NULL CHECK (updated_at GLOB '????-??-??T??:??:??*Z'),
    PRIMARY KEY (binding_id, namespace, storage_key)
) STRICT;

CREATE INDEX ix_plugin_storage_namespace ON plugin_storage(binding_id, namespace);
