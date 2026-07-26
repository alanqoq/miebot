package com.mieai.qqbot.persistence.inbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.RowMapper

internal class InboxEventRowMapper : RowMapper<InboxEvent> {
    override fun mapRow(resultSet: ResultSet, rowNumber: Int): InboxEvent = InboxEvent(
        UUID.fromString(resultSet.getString("id")),
        BotEnvironment.valueOf(resultSet.getString("environment")),
        BotId.parse(resultSet.getString("bot_id")),
        resultSet.getString("event_type"),
        resultSet.getString("platform_event_id"),
        resultSet.getString("payload"),
        InboxStatus.valueOf(resultSet.getString("status")),
        resultSet.getInt("attempt"),
        UtcTimestampCodec.parse(resultSet.getString("available_at")),
        resultSet.getString("lease_owner"),
        resultSet.getString("lease_until")?.let(UtcTimestampCodec::parse),
        resultSet.getLong("fencing_token"),
        resultSet.getString("last_error"),
        UtcTimestampCodec.parse(resultSet.getString("received_at")),
        UtcTimestampCodec.parse(resultSet.getString("updated_at")),
    )
}
