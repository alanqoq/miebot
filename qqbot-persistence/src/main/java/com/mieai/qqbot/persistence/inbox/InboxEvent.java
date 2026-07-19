package com.mieai.qqbot.persistence.inbox;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable representation of a platform event and its reserved processing lease fields. */
public record InboxEvent(
        UUID id,
        BotEnvironment environment,
        BotId botId,
        String eventType,
        String platformEventId,
        String payload,
        InboxStatus status,
        int attempt,
        Instant availableAt,
        Optional<String> leaseOwner,
        Optional<Instant> leaseUntil,
        long fencingToken,
        Optional<String> lastError,
        Instant receivedAt,
        Instant updatedAt) {

    public InboxEvent {
        requireIdentifier(id, "id");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        requireToken(eventType, "eventType", 128);
        requireToken(platformEventId, "platformEventId", 255);
        requirePayload(payload, "payload");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (fencingToken < 0L) {
            throw new IllegalArgumentException("fencingToken must not be negative");
        }
        Objects.requireNonNull(lastError, "lastError must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        leaseOwner.ifPresent(owner -> requireToken(owner, "leaseOwner"));
        lastError.ifPresent(error -> requirePayload(error, "lastError"));
        boolean processing = status == InboxStatus.PROCESSING;
        boolean hasOwner = leaseOwner.isPresent();
        boolean hasDeadline = leaseUntil.isPresent();
        if ((processing && (!hasOwner || !hasDeadline))
                || (!processing && (hasOwner || hasDeadline))) {
            throw new IllegalArgumentException("processing status and lease fields must be consistent");
        }
        if (updatedAt.isBefore(receivedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before receivedAt");
        }
    }
}
