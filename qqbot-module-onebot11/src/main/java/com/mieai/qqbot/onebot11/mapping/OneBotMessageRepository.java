package com.mieai.qqbot.onebot11.mapping;

import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotId;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Message context required by OneBot int32 IDs, replies, lookup, and recall. */
public final class OneBotMessageRepository {
    private static final String COLUMNS = """
            message_id, bot_id, official_message_id, target_type, target_raw_id,
            direction, message_type, event_time, user_id, message_json, sender_json
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OneBotMessageRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    OneBotMessageRepository(DataSource dataSource, Clock clock) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public int record(
            BotId botId,
            String officialMessageId,
            QqMessageTargetType targetType,
            String targetRawId,
            OneBotStoredMessage.Direction direction,
            String messageType,
            long eventTime,
            long userId,
            String messageJson,
            String senderJson) {
        validate(botId, officialMessageId, targetType, targetRawId, direction,
                messageType, eventTime, userId, messageJson, senderJson);
        Optional<OneBotStoredMessage> existing = findByOfficial(botId, officialMessageId);
        if (existing.isPresent()) {
            return existing.orElseThrow().messageId();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO onebot11_messages (
                            bot_id, official_message_id, target_type, target_raw_id,
                            direction, message_type, event_time, user_id,
                            message_json, sender_json, created_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, botId.toString());
                statement.setString(2, officialMessageId);
                statement.setString(3, targetType.name());
                statement.setString(4, targetRawId);
                statement.setString(5, direction.name());
                statement.setString(6, messageType);
                statement.setLong(7, eventTime);
                statement.setLong(8, userId);
                statement.setString(9, messageJson);
                statement.setString(10, senderJson);
                statement.setString(11, clock.instant().toString());
                return statement;
            }, keys);
            Number key = keys.getKey();
            if (key != null && key.longValue() <= Integer.MAX_VALUE) {
                return key.intValue();
            }
        } catch (DuplicateKeyException ignored) {
            // A retried Gateway event may race with the original insert.
        }
        return findByOfficial(botId, officialMessageId)
                .map(OneBotStoredMessage::messageId)
                .orElseThrow(() -> new IllegalStateException("Message ID was not generated"));
    }

    public Optional<OneBotStoredMessage> find(BotId botId, int messageId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM onebot11_messages WHERE bot_id=? AND message_id=?",
                OneBotMessageRepository::map, botId.toString(), messageId).stream().findFirst();
    }

    public Optional<OneBotStoredMessage> findByOfficial(BotId botId, String officialMessageId) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(officialMessageId, "officialMessageId must not be null");
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM onebot11_messages WHERE bot_id=? AND official_message_id=?",
                OneBotMessageRepository::map, botId.toString(), officialMessageId)
                .stream().findFirst();
    }

    private static OneBotStoredMessage map(java.sql.ResultSet resultSet, int rowNumber)
            throws java.sql.SQLException {
        return new OneBotStoredMessage(
                resultSet.getInt("message_id"),
                BotId.parse(resultSet.getString("bot_id")),
                resultSet.getString("official_message_id"),
                QqMessageTargetType.valueOf(resultSet.getString("target_type")),
                resultSet.getString("target_raw_id"),
                OneBotStoredMessage.Direction.valueOf(resultSet.getString("direction")),
                resultSet.getString("message_type"),
                resultSet.getLong("event_time"),
                resultSet.getLong("user_id"),
                resultSet.getString("message_json"),
                resultSet.getString("sender_json"));
    }

    private static void validate(
            BotId botId, String officialMessageId, QqMessageTargetType targetType,
            String targetRawId, OneBotStoredMessage.Direction direction,
            String messageType, long eventTime, long userId,
            String messageJson, String senderJson) {
        Objects.requireNonNull(botId, "botId must not be null");
        requireText(officialMessageId, "officialMessageId", 512);
        if (targetType != QqMessageTargetType.C2C && targetType != QqMessageTargetType.GROUP) {
            throw new IllegalArgumentException("Only C2C and group messages are supported");
        }
        requireText(targetRawId, "targetRawId", 512);
        Objects.requireNonNull(direction, "direction must not be null");
        if (!messageType.equals("private") && !messageType.equals("group")) {
            throw new IllegalArgumentException("messageType is invalid");
        }
        if (eventTime < 0L || userId < 1L) {
            throw new IllegalArgumentException("message metadata is invalid");
        }
        requireText(messageJson, "messageJson", 1_000_000);
        requireText(senderJson, "senderJson", 1_000_000);
    }

    private static void requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
