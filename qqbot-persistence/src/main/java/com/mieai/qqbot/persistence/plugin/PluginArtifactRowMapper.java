package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class PluginArtifactRowMapper implements RowMapper<PluginArtifact> {
    @Override
    public PluginArtifact mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new PluginArtifact(rs.getString("plugin_id"), rs.getString("name"), rs.getString("version"),
                rs.getString("api_compatibility"), rs.getString("file_name"), rs.getString("sha256"),
                rs.getString("entrypoint"), rs.getString("status"), rs.getInt("enabled") != 0,
                UtcTimestampCodec.parse(rs.getString("created_at")),
                UtcTimestampCodec.parse(rs.getString("updated_at")));
    }
}
