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

/** Durable Outbox job including attempt count, lease, and fencing state. */
public record OutboxJob(
        UUID id,
        BotEnvironment environment,
        BotId botId,
        Optional<UUID> sourceEventId,
        String jobType,
        Optional<String> dedupKey,
        String payload,
        OutboxStatus status,
        long attempt,
        Instant availableAt,
        Optional<String> leaseOwner,
        Optional<Instant> leaseUntil,
        long fencingToken,
        Optional<String> lastError,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {

    public OutboxJob {
        requireIdentifier(id, "id");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        sourceEventId.ifPresent(value -> requireIdentifier(value, "sourceEventId"));
        requireToken(jobType, "jobType");
        Objects.requireNonNull(dedupKey, "dedupKey must not be null");
        dedupKey.ifPresent(value -> requireToken(value, "dedupKey"));
        requirePayload(payload, "payload");
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0L) {
            throw new IllegalArgumentException("attempt must not be negative");
        }
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (fencingToken < 0L) {
            throw new IllegalArgumentException("fencingToken must not be negative");
        }
        Objects.requireNonNull(lastError, "lastError must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");

        leaseOwner.ifPresent(owner -> requireToken(owner, "leaseOwner"));
        lastError.ifPresent(error -> requirePayload(error, "lastError"));
        boolean hasOwner = leaseOwner.isPresent();
        boolean hasDeadline = leaseUntil.isPresent();
        if (status == OutboxStatus.IN_PROGRESS) {
            if (!hasOwner || !hasDeadline) {
                throw new IllegalArgumentException("in-progress job must have a complete lease");
            }
        } else if (hasOwner || hasDeadline) {
            throw new IllegalArgumentException("job outside in-progress state must not have a lease");
        }
        if (status.isTerminal() != completedAt.isPresent()) {
            throw new IllegalArgumentException("terminal status and completedAt must be consistent");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
