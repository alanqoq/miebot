package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PluginDelivery(
        UUID id,
        UUID eventId,
        UUID bindingId,
        String handlerId,
        PluginDeliveryStatus status,
        int attempt,
        Instant availableAt,
        Optional<String> leaseOwner,
        Optional<Instant> leaseUntil,
        long fencingToken,
        Optional<String> lastError,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> completedAt) {
    public PluginDelivery {
        requireIdentifier(id, "id");
        requireIdentifier(eventId, "eventId");
        requireIdentifier(bindingId, "bindingId");
        requireToken(handlerId, "handlerId", 128);
        Objects.requireNonNull(status, "status must not be null");
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        Objects.requireNonNull(availableAt, "availableAt must not be null");
        Objects.requireNonNull(leaseOwner, "leaseOwner must not be null");
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (fencingToken < 0) throw new IllegalArgumentException("fencingToken must not be negative");
        Objects.requireNonNull(lastError, "lastError must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        leaseOwner.ifPresent(value -> requireToken(value, "leaseOwner", 255));
        lastError.ifPresent(value -> requirePayload(value, "lastError"));
        if (status == PluginDeliveryStatus.IN_PROGRESS
                && (leaseOwner.isEmpty() || leaseUntil.isEmpty())) {
            throw new IllegalArgumentException("in-progress delivery must have a lease");
        }
        if (status != PluginDeliveryStatus.IN_PROGRESS
                && (leaseOwner.isPresent() || leaseUntil.isPresent())) {
            throw new IllegalArgumentException("non-running delivery must not have a lease");
        }
        if (status.terminal() != completedAt.isPresent()) {
            throw new IllegalArgumentException("terminal status and completedAt must be consistent");
        }
    }
}
