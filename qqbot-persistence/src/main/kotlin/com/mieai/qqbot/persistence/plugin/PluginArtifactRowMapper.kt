package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.sql.ResultSet
import org.springframework.jdbc.core.RowMapper

internal class PluginArtifactRowMapper : RowMapper<PluginArtifact> {
    override fun mapRow(resultSet: ResultSet, rowNumber: Int): PluginArtifact = PluginArtifact(
        resultSet.getString("plugin_id"),
        resultSet.getString("name"),
        resultSet.getString("version"),
        resultSet.getString("api_compatibility"),
        resultSet.getString("file_name"),
        resultSet.getString("sha256"),
        resultSet.getString("entrypoint"),
        resultSet.getString("status"),
        resultSet.getInt("enabled") != 0,
        UtcTimestampCodec.parse(resultSet.getString("created_at")),
        UtcTimestampCodec.parse(resultSet.getString("updated_at")),
    )
}
