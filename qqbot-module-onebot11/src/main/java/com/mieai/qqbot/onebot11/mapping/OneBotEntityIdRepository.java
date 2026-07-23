package com.mieai.qqbot.onebot11.mapping;

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

/** Stable numeric aliases for QQ string identifiers. */
public final class OneBotEntityIdRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public OneBotEntityIdRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    OneBotEntityIdRepository(DataSource dataSource, Clock clock) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public long aliasFor(
            BotId botId, OneBotEntityType type, String scopeId, String rawId) {
        require(botId, type, scopeId, rawId);
        Optional<Long> existing = findAlias(botId, type, scopeId, rawId);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO onebot11_entity_ids (
                            bot_id, entity_type, scope_id, raw_id, created_at
                        ) VALUES (?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, botId.toString());
                statement.setString(2, type.name());
                statement.setString(3, scopeId);
                statement.setString(4, rawId);
                statement.setString(5, clock.instant().toString());
                return statement;
            }, keys);
            Number key = keys.getKey();
            if (key != null) {
                return key.longValue();
            }
        } catch (DuplicateKeyException ignored) {
            // A concurrent event inserted the same identity; load its stable alias below.
        }
        return findAlias(botId, type, scopeId, rawId)
                .orElseThrow(() -> new IllegalStateException("Entity alias was not generated"));
    }

    public Optional<OneBotEntityMapping> find(BotId botId, long oneBotId) {
        Objects.requireNonNull(botId, "botId must not be null");
        if (oneBotId < 1L) {
            return Optional.empty();
        }
        List<OneBotEntityMapping> matches = jdbc.query("""
                SELECT onebot_id, entity_type, scope_id, raw_id
                FROM onebot11_entity_ids WHERE bot_id=? AND onebot_id=?
                """, (resultSet, rowNumber) -> new OneBotEntityMapping(
                        resultSet.getLong("onebot_id"),
                        OneBotEntityType.valueOf(resultSet.getString("entity_type")),
                        resultSet.getString("scope_id"),
                        resultSet.getString("raw_id")),
                botId.toString(), oneBotId);
        return matches.stream().findFirst();
    }

    public OneBotEntityMapping require(
            BotId botId, long oneBotId, OneBotEntityType expectedType) {
        OneBotEntityMapping mapping = find(botId, oneBotId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown OneBot identifier"));
        if (mapping.type() != expectedType) {
            throw new IllegalArgumentException("OneBot identifier has the wrong type");
        }
        return mapping;
    }

    private Optional<Long> findAlias(
            BotId botId, OneBotEntityType type, String scopeId, String rawId) {
        return jdbc.query("""
                SELECT onebot_id FROM onebot11_entity_ids
                WHERE bot_id=? AND entity_type=? AND scope_id=? AND raw_id=?
                """, (resultSet, rowNumber) -> resultSet.getLong(1),
                botId.toString(), type.name(), scopeId, rawId).stream().findFirst();
    }

    private static void require(
            BotId botId, OneBotEntityType type, String scopeId, String rawId) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        requireToken(scopeId, "scopeId", true);
        requireToken(rawId, "rawId", false);
    }

    private static void requireToken(String value, String name, boolean emptyAllowed) {
        Objects.requireNonNull(value, name + " must not be null");
        if ((!emptyAllowed && value.isBlank()) || !value.equals(value.strip())
                || value.length() > 512
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
