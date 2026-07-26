package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.RowMapper

internal class BotPluginBindingRowMapper : RowMapper<BotPluginBinding> {
    override fun mapRow(resultSet: ResultSet, rowNumber: Int): BotPluginBinding = BotPluginBinding(
        UUID.fromString(resultSet.getString("id")),
        resultSet.getString("plugin_id"),
        BotId.parse(resultSet.getString("bot_id")),
        resultSet.getInt("enabled") != 0,
        resultSet.getLong("revision"),
        UtcTimestampCodec.parse(resultSet.getString("created_at")),
        UtcTimestampCodec.parse(resultSet.getString("updated_at")),
        PluginBindingRuntimeState.valueOf(resultSet.getString("runtime_state")),
        resultSet.getString("runtime_error"),
    )
}
