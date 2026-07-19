package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class BotPluginBindingRowMapper implements RowMapper<BotPluginBinding> {
    @Override
    public BotPluginBinding mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BotPluginBinding(UUID.fromString(rs.getString("id")), rs.getString("plugin_id"),
                BotId.parse(rs.getString("bot_id")), rs.getString("config_json"), rs.getInt("enabled") != 0,
                rs.getLong("revision"), UtcTimestampCodec.parse(rs.getString("created_at")),
                UtcTimestampCodec.parse(rs.getString("updated_at")));
    }
}
