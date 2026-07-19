package com.mieai.qqbot.persistence.inbox;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.internal.UtcTimestampCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

final class InboxEventRowMapper implements RowMapper<InboxEvent> {
    @Override
    public InboxEvent mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        String leaseOwner = resultSet.getString("lease_owner");
        String leaseUntil = resultSet.getString("lease_until");
        String lastError = resultSet.getString("last_error");
        return new InboxEvent(
                UUID.fromString(resultSet.getString("id")),
                BotEnvironment.valueOf(resultSet.getString("environment")),
                BotId.parse(resultSet.getString("bot_id")),
                resultSet.getString("event_type"),
                resultSet.getString("platform_event_id"),
                resultSet.getString("payload"),
                InboxStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("attempt"),
                UtcTimestampCodec.parse(resultSet.getString("available_at")),
                Optional.ofNullable(leaseOwner),
                Optional.ofNullable(leaseUntil).map(UtcTimestampCodec::parse),
                resultSet.getLong("fencing_token"),
                Optional.ofNullable(lastError),
                UtcTimestampCodec.parse(resultSet.getString("received_at")),
                UtcTimestampCodec.parse(resultSet.getString("updated_at")));
    }
}
