package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import javax.sql.DataSource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

/** JDBC artifact catalog. Upsert is written without vendor-specific syntax. */
class JdbcPluginArtifactRepository(dataSource: DataSource) : PluginArtifactRepository {
    private val jdbc = JdbcTemplate(dataSource)

    override fun upsert(artifact: PluginArtifact) {
        val now = UtcTimestampCodec.format(artifact.updatedAt)
        val updated = jdbc.update(
            """
            UPDATE plugin_artifacts SET name=?, version=?, api_compatibility=?, file_name=?,
                sha256=?, entrypoint=?, status=?, enabled=?, updated_at=? WHERE plugin_id=?
            """.trimIndent(),
            artifact.name,
            artifact.version,
            artifact.apiCompatibility,
            artifact.fileName,
            artifact.sha256,
            artifact.entrypoint,
            artifact.status,
            if (artifact.enabled) 1 else 0,
            now,
            artifact.pluginId,
        )
        if (updated == 1) return
        try {
            jdbc.update(
                """
                INSERT INTO plugin_artifacts (plugin_id, name, version, api_compatibility, file_name,
                    sha256, entrypoint, status, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                artifact.pluginId,
                artifact.name,
                artifact.version,
                artifact.apiCompatibility,
                artifact.fileName,
                artifact.sha256,
                artifact.entrypoint,
                artifact.status,
                if (artifact.enabled) 1 else 0,
                UtcTimestampCodec.format(artifact.createdAt),
                now,
            )
        } catch (_: DataAccessException) {
            // A concurrent scanner may have inserted the same artifact; make the update authoritative.
            jdbc.update(
                """
                UPDATE plugin_artifacts SET name=?, version=?, api_compatibility=?, file_name=?,
                    sha256=?, entrypoint=?, status=?, enabled=?, updated_at=? WHERE plugin_id=?
                """.trimIndent(),
                artifact.name,
                artifact.version,
                artifact.apiCompatibility,
                artifact.fileName,
                artifact.sha256,
                artifact.entrypoint,
                artifact.status,
                if (artifact.enabled) 1 else 0,
                now,
                artifact.pluginId,
            )
        }
    }

    override fun findById(pluginId: String): PluginArtifact? = jdbc.query(
        "SELECT $COLUMNS FROM plugin_artifacts WHERE plugin_id = ?",
        PluginArtifactRowMapper(),
        pluginId,
    ).firstOrNull()

    override fun findAll(): List<PluginArtifact> =
        jdbc.query(
            "SELECT $COLUMNS FROM plugin_artifacts ORDER BY plugin_id",
            PluginArtifactRowMapper(),
        ).toList()

    private companion object {
        const val COLUMNS =
            "plugin_id, name, version, api_compatibility, file_name, sha256, entrypoint, status, enabled, created_at, updated_at"
    }
}
