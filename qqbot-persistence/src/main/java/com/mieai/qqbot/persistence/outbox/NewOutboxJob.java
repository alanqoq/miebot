package com.mieai.qqbot.persistence.outbox;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** New Outbox job before its first processing attempt. */
public record NewOutboxJob(
        UUID id,
        BotEnvironment environment,
        BotId botId,
        Optional<UUID> sourceEventId,
        String jobType,
        Optional<String> dedupKey,
        String payload,
        Instant availableAt,
        Instant createdAt) {

    public NewOutboxJob {
        requireIdentifier(id, "id");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        sourceEventId.ifPresent(value -> requireIdentifier(value, "sourceEventId"));
        requireToken(jobType, "jobType");
        Objects.requireNonNull(dedupKey, "dedupKey must not be null");
        dedupKey.ifPresent(value -> requireToken(value, "dedupKey"));
        requirePayload(payload, "payload");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (availableAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("availableAt must not be before createdAt");
        }
    }
}
