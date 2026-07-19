CREATE TABLE plugin_storage (
    binding_id            VARCHAR(36) NOT NULL,
    namespace             VARCHAR(64) NOT NULL,
    storage_key           VARCHAR(128) NOT NULL,
    value_text            LONGTEXT NOT NULL,
    updated_at            VARCHAR(40) NOT NULL,
    PRIMARY KEY (binding_id, namespace, storage_key),
    CONSTRAINT fk_plugin_storage_binding FOREIGN KEY (binding_id) REFERENCES bot_plugins(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE INDEX ix_plugin_storage_namespace ON plugin_storage(binding_id, namespace);
