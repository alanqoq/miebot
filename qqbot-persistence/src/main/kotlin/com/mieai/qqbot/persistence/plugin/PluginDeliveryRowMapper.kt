package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.RowMapper

internal class PluginDeliveryRowMapper : RowMapper<PluginDelivery> {
    override fun mapRow(resultSet: ResultSet, rowNumber: Int): PluginDelivery = PluginDelivery(
        UUID.fromString(resultSet.getString("id")),
        UUID.fromString(resultSet.getString("event_id")),
        UUID.fromString(resultSet.getString("binding_id")),
        resultSet.getString("handler_id"),
        PluginDeliveryStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempt"),
        UtcTimestampCodec.parse(resultSet.getString("available_at")),
        resultSet.getString("lease_owner"),
        resultSet.getString("lease_until")?.let(UtcTimestampCodec::parse),
        resultSet.getLong("fencing_token"),
        resultSet.getString("last_error"),
        UtcTimestampCodec.parse(resultSet.getString("created_at")),
        UtcTimestampCodec.parse(resultSet.getString("updated_at")),
        resultSet.getString("completed_at")?.let(UtcTimestampCodec::parse),
    )
}
