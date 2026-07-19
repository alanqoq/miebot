package com.mieai.qqbot.persistence.outbox;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.util.Objects;
import java.util.Optional;

/** Bounded, cursor-based management query for durable Outbox jobs. */
public record OutboxQuery(
        int limit,
        Optional<String> cursor,
        Optional<BotEnvironment> environment,
        Optional<BotId> botId,
        Optional<OutboxStatus> status,
        Optional<String> jobType,
        Optional<String> search) {

    public OutboxQuery {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        cursor = requireOptionalToken(cursor, "cursor", 512);
        environment = Objects.requireNonNull(environment, "environment must not be null");
        botId = Objects.requireNonNull(botId, "botId must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        jobType = requireOptionalToken(jobType, "jobType", 128);
        search = requireOptionalSearch(search);
    }

    public static OutboxQuery firstPage(int limit) {
        return new OutboxQuery(
                limit,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Optional<String> requireOptionalToken(
            Optional<String> value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(token -> {
            Objects.requireNonNull(token, name + " value must not be null");
            if (token.isBlank()
                    || !token.equals(token.strip())
                    || token.codePoints().anyMatch(Character::isWhitespace)
                    || token.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " must be a non-blank token");
            }
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
            if (search.isBlank()
                    || !search.equals(search.strip())
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
