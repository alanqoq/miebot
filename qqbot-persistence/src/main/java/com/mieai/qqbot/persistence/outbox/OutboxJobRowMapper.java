package com.mieai.qqbot.persistence.outbox;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class OutboxJobRowMapper implements RowMapper<OutboxJob> {
    @Override
    public OutboxJob mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String sourceEventId = resultSet.getString("source_event_id");
        String dedupKey = resultSet.getString("dedup_key");
        String leaseOwner = resultSet.getString("lease_owner");
        String leaseUntil = resultSet.getString("lease_until");
        String lastError = resultSet.getString("last_error");
        String completedAt = resultSet.getString("completed_at");
        return new OutboxJob(
                UUID.fromString(resultSet.getString("id")),
                BotEnvironment.valueOf(resultSet.getString("environment")),
                BotId.parse(resultSet.getString("bot_id")),
                Optional.ofNullable(sourceEventId).map(UUID::fromString),
                resultSet.getString("job_type"),
                Optional.ofNullable(dedupKey),
                resultSet.getString("payload"),
                OutboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("attempt"),
                UtcTimestampCodec.parse(resultSet.getString("available_at")),
                Optional.ofNullable(leaseOwner),
                Optional.ofNullable(leaseUntil).map(UtcTimestampCodec::parse),
                resultSet.getLong("fencing_token"),
                Optional.ofNullable(lastError),
                UtcTimestampCodec.parse(resultSet.getString("created_at")),
                UtcTimestampCodec.parse(resultSet.getString("updated_at")),
                Optional.ofNullable(completedAt).map(UtcTimestampCodec::parse));
    }
}
