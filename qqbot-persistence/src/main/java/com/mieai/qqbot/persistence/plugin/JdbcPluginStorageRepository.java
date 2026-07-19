package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier;
import com.mieai.qqbot.persistence.internal.PersistenceValidation;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPluginStorageRepository implements PluginStorageRepository {
    private static final int MAX_NAMESPACE_LENGTH = 64;
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_VALUE_BYTES = 64 * 1024;
    private final JdbcTemplate jdbc;

    public JdbcPluginStorageRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Optional<String> find(UUID bindingId, String namespace, String key) {
        validateScope(bindingId, namespace);
        validateKey(key);
        return jdbc.query("SELECT value_text FROM plugin_storage WHERE binding_id=? AND namespace=? AND storage_key=?",
                (rs, row) -> rs.getString(1), bindingId.toString(), namespace, key).stream().findFirst();
    }

    @Override
    public void put(UUID bindingId, String namespace, String key, String value, Instant updatedAt) {
        validateScope(bindingId, namespace);
        validateKey(key);
        validateValue(value);
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        String timestamp = UtcTimestampCodec.format(updatedAt);
        int updated = jdbc.update("UPDATE plugin_storage SET value_text=?, updated_at=? WHERE binding_id=? AND namespace=? AND storage_key=?",
                value, timestamp, bindingId.toString(), namespace, key);
        if (updated == 1) return;
        try {
            jdbc.update("INSERT INTO plugin_storage (binding_id, namespace, storage_key, value_text, updated_at) VALUES (?, ?, ?, ?, ?)",
                    bindingId.toString(), namespace, key, value, timestamp);
        } catch (DataAccessException duplicate) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(duplicate)) throw duplicate;
            jdbc.update("UPDATE plugin_storage SET value_text=?, updated_at=? WHERE binding_id=? AND namespace=? AND storage_key=?",
                    value, timestamp, bindingId.toString(), namespace, key);
        }
    }

    @Override
    public void delete(UUID bindingId, String namespace, String key) {
        validateScope(bindingId, namespace);
        validateKey(key);
        jdbc.update("DELETE FROM plugin_storage WHERE binding_id=? AND namespace=? AND storage_key=?",
                bindingId.toString(), namespace, key);
    }

    @Override
    public Map<String, String> list(UUID bindingId, String namespace) {
        validateScope(bindingId, namespace);
        Map<String, String> values = new LinkedHashMap<>();
        jdbc.query("SELECT storage_key, value_text FROM plugin_storage WHERE binding_id=? AND namespace=? ORDER BY storage_key",
                (rs, row) -> Map.entry(rs.getString(1), rs.getString(2)), bindingId.toString(), namespace)
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }

    private static void validateScope(UUID bindingId, String namespace) {
        PersistenceValidation.requireIdentifier(bindingId, "bindingId");
        PersistenceValidation.requireToken(namespace, "namespace", MAX_NAMESPACE_LENGTH);
    }

    private static void validateKey(String key) {
        PersistenceValidation.requireToken(key, "key", MAX_KEY_LENGTH);
    }

    private static void validateValue(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("value exceeds 64 KiB");
        }
    }
}
