package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** JDBC artifact catalog. Upsert is written without vendor-specific syntax. */
public final class JdbcPluginArtifactRepository implements PluginArtifactRepository {
    private static final String COLUMNS = "plugin_id, name, version, api_compatibility, file_name, sha256, entrypoint, status, enabled, created_at, updated_at";
    private final JdbcTemplate jdbc;

    public JdbcPluginArtifactRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void upsert(PluginArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        String now = UtcTimestampCodec.format(artifact.updatedAt());
        int updated = jdbc.update("""
                UPDATE plugin_artifacts SET name=?, version=?, api_compatibility=?, file_name=?,
                    sha256=?, entrypoint=?, status=?, enabled=?, updated_at=? WHERE plugin_id=?
                """, artifact.name(), artifact.version(), artifact.apiCompatibility(), artifact.fileName(),
                artifact.sha256(), artifact.entrypoint(), artifact.status(), artifact.enabled() ? 1 : 0,
                now, artifact.pluginId());
        if (updated == 1) return;
        try {
            jdbc.update("""
                    INSERT INTO plugin_artifacts (plugin_id, name, version, api_compatibility, file_name,
                        sha256, entrypoint, status, enabled, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, artifact.pluginId(), artifact.name(), artifact.version(), artifact.apiCompatibility(),
                    artifact.fileName(), artifact.sha256(), artifact.entrypoint(), artifact.status(),
                    artifact.enabled() ? 1 : 0, UtcTimestampCodec.format(artifact.createdAt()), now);
        } catch (DataAccessException race) {
            // A concurrent scanner may have inserted the same artifact; make the update authoritative.
            jdbc.update("""
                    UPDATE plugin_artifacts SET name=?, version=?, api_compatibility=?, file_name=?,
                        sha256=?, entrypoint=?, status=?, enabled=?, updated_at=? WHERE plugin_id=?
                    """, artifact.name(), artifact.version(), artifact.apiCompatibility(), artifact.fileName(),
                    artifact.sha256(), artifact.entrypoint(), artifact.status(), artifact.enabled() ? 1 : 0,
                    now, artifact.pluginId());
        }
    }

    @Override
    public Optional<PluginArtifact> findById(String pluginId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM plugin_artifacts WHERE plugin_id = ?",
                        new PluginArtifactRowMapper(), pluginId).stream().findFirst();
    }

    @Override
    public List<PluginArtifact> findAll() {
        return List.copyOf(jdbc.query("SELECT " + COLUMNS + " FROM plugin_artifacts ORDER BY plugin_id",
                new PluginArtifactRowMapper()));
    }
}
