package com.mieai.qqbot.persistence.plugin;

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class PluginDeliveryRowMapper implements RowMapper<PluginDelivery> {
    @Override
    public PluginDelivery mapRow(ResultSet rs, int rowNum) throws SQLException {
        String owner = rs.getString("lease_owner");
        String until = rs.getString("lease_until");
        String error = rs.getString("last_error");
        String completed = rs.getString("completed_at");
        return new PluginDelivery(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("event_id")),
                UUID.fromString(rs.getString("binding_id")), rs.getString("handler_id"),
                PluginDeliveryStatus.valueOf(rs.getString("status")), rs.getInt("attempt"),
                UtcTimestampCodec.parse(rs.getString("available_at")), Optional.ofNullable(owner),
                until == null ? Optional.empty() : Optional.of(UtcTimestampCodec.parse(until)),
                rs.getLong("fencing_token"), Optional.ofNullable(error),
                UtcTimestampCodec.parse(rs.getString("created_at")),
                UtcTimestampCodec.parse(rs.getString("updated_at")),
                completed == null ? Optional.empty() : Optional.of(UtcTimestampCodec.parse(completed)));
    }
}
