package com.mieai.qqbot.persistence.inbox;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.util.Objects;
import java.util.Optional;

/** Bounded, cursor-based Inbox query shared by the HTTP adapter and JDBC implementation. */
public record InboxQuery(
        int limit,
        Optional<String> cursor,
        Optional<BotEnvironment> environment,
        Optional<BotId> botId,
        Optional<InboxStatus> status,
        Optional<String> eventType,
        Optional<String> search) {

    public InboxQuery {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        cursor = requireOptionalToken(cursor, "cursor");
        environment = Objects.requireNonNull(environment, "environment must not be null");
        botId = Objects.requireNonNull(botId, "botId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        eventType = requireOptionalToken(eventType, "eventType");
        search = requireOptionalSearch(search);
    }

    public static InboxQuery firstPage(int limit) {
        return new InboxQuery(
                limit,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<String> requireOptionalToken(Optional<String> value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(token -> {
            Objects.requireNonNull(token, name + " value must not be null");
            if (token.isBlank() || !token.equals(token.strip())
                    || token.codePoints().anyMatch(Character::isWhitespace)
                    || token.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " must be a non-blank token");
            }
            int maximumLength = "cursor".equals(name) ? 512 : 128;
            if (token.length() > maximumLength) {
                throw new IllegalArgumentException(name + " is too long");
            }
            return token;
        });
    }

    private static Optional<String> requireOptionalSearch(Optional<String> value) {
        Objects.requireNonNull(value, "search must not be null");
        return value.map(search -> {
            Objects.requireNonNull(search, "search value must not be null");
            if (search.isBlank() || !search.equals(search.strip())
                    || search.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("search must be a non-blank value");
            }
            if (search.length() > 256) {
                throw new IllegalArgumentException("search is too long");
            }
            return search;
        });
    }
}
