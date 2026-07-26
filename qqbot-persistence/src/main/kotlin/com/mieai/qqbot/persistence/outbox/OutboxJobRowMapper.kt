package com.mieai.qqbot.persistence.outbox

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.RowMapper

internal class OutboxJobRowMapper : RowMapper<OutboxJob> {
    override fun mapRow(resultSet: ResultSet, rowNumber: Int): OutboxJob {
        val sequence = resultSet.getInt("platform_message_sequence")
        val platformMessageSequence = if (resultSet.wasNull()) null else sequence
        return OutboxJob(
            UUID.fromString(resultSet.getString("id")),
            BotEnvironment.valueOf(resultSet.getString("environment")),
            BotId.parse(resultSet.getString("bot_id")),
            resultSet.getString("source_event_id")?.let(UUID::fromString),
            resultSet.getString("job_type"),
            resultSet.getString("dedup_key"),
            resultSet.getString("payload"),
            OutboxStatus.valueOf(resultSet.getString("status")),
            resultSet.getLong("attempt"),
            UtcTimestampCodec.parse(resultSet.getString("available_at")),
            resultSet.getString("lease_owner"),
            resultSet.getString("lease_until")?.let(UtcTimestampCodec::parse),
            resultSet.getLong("fencing_token"),
            resultSet.getString("last_error"),
            UtcTimestampCodec.parse(resultSet.getString("created_at")),
            UtcTimestampCodec.parse(resultSet.getString("updated_at")),
            resultSet.getString("completed_at")?.let(UtcTimestampCodec::parse),
            resultSet.getString("producer_binding_id")?.let(UUID::fromString),
            resultSet.getString("platform_message_id"),
            platformMessageSequence,
            resultSet.getString("platform_timestamp"),
        )
    }
}
