package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import com.mieai.qqbot.persistence.internal.PersistenceValidation
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import javax.sql.DataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class JdbcPluginStorageRepository(dataSource: DataSource) : PluginStorageRepository {
    private val jdbc = JdbcTemplate(dataSource)

    override fun find(bindingId: UUID, namespace: String, key: String): String? {
        validateScope(bindingId, namespace)
        validateKey(key)
        return jdbc.query(
            "SELECT value_text FROM plugin_storage WHERE binding_id=? AND namespace=? AND storage_key=?",
            RowMapper { resultSet, _ -> resultSet.getString(1) },
            bindingId.toString(),
            namespace,
            key,
        ).firstOrNull()
    }

    override fun put(bindingId: UUID, namespace: String, key: String, value: String, updatedAt: Instant) {
        validateScope(bindingId, namespace)
        validateKey(key)
        validateValue(value)
        val timestamp = UtcTimestampCodec.format(updatedAt)
        val updated = jdbc.update(
            "UPDATE plugin_storage SET value_text=?, updated_at=? WHERE binding_id=? AND namespace=? AND storage_key=?",
            value,
            timestamp,
            bindingId.toString(),
            namespace,
            key,
        )
        if (updated == 1) return
        try {
            jdbc.update(
                "INSERT INTO plugin_storage (binding_id, namespace, storage_key, value_text, updated_at) VALUES (?, ?, ?, ?, ?)",
                bindingId.toString(),
                namespace,
                key,
                value,
                timestamp,
            )
        } catch (duplicate: DataAccessException) {
            if (!DatabaseExceptionClassifier.isDuplicateKey(duplicate)) throw duplicate
            jdbc.update(
                "UPDATE plugin_storage SET value_text=?, updated_at=? WHERE binding_id=? AND namespace=? AND storage_key=?",
                value,
                timestamp,
                bindingId.toString(),
                namespace,
                key,
            )
        }
    }

    override fun delete(bindingId: UUID, namespace: String, key: String) {
        validateScope(bindingId, namespace)
        validateKey(key)
        jdbc.update(
            "DELETE FROM plugin_storage WHERE binding_id=? AND namespace=? AND storage_key=?",
            bindingId.toString(),
            namespace,
            key,
        )
    }

    override fun list(bindingId: UUID, namespace: String): Map<String, String> {
        validateScope(bindingId, namespace)
        val values = LinkedHashMap<String, String>()
        jdbc.query(
            "SELECT storage_key, value_text FROM plugin_storage WHERE binding_id=? AND namespace=? ORDER BY storage_key",
            RowMapper { resultSet, _ -> resultSet.getString(1) to resultSet.getString(2) },
            bindingId.toString(),
            namespace,
        ).forEach { (key, value) -> values[key] = value }
        return values.toMap()
    }

    private fun validateScope(bindingId: UUID, namespace: String) {
        PersistenceValidation.requireIdentifier(bindingId, "bindingId")
        PersistenceValidation.requireToken(namespace, "namespace", MAX_NAMESPACE_LENGTH)
    }

    private fun validateKey(key: String) {
        PersistenceValidation.requireToken(key, "key", MAX_KEY_LENGTH)
    }

    private fun validateValue(value: String) {
        require(value.toByteArray(StandardCharsets.UTF_8).size <= MAX_VALUE_BYTES) { "value exceeds 64 KiB" }
    }

    private companion object {
        const val MAX_NAMESPACE_LENGTH = 64
        const val MAX_KEY_LENGTH = 128
        const val MAX_VALUE_BYTES = 64 * 1024
    }
}
