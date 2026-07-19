package com.mieai.qqbot.persistence.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Sanitized administrator mutation record; request bodies are never stored. */
public record AuditLog(
        UUID id,
        Optional<String> actorUsername,
        String action,
        String resourcePath,
        int outcomeStatus,
        Optional<String> remoteAddress,
        Optional<String> traceId,
        Instant createdAt) {

    public AuditLog {
        Objects.requireNonNull(id, "id must not be null");
        actorUsername = requireOptional(actorUsername, "actorUsername", 64);
        action = requireText(action, "action", 16);
        resourcePath = requireText(resourcePath, "resourcePath", 512);
        if (outcomeStatus < 100 || outcomeStatus > 599) {
            throw new IllegalArgumentException("outcomeStatus must be a valid HTTP status");
        }
        remoteAddress = requireOptional(remoteAddress, "remoteAddress", 128);
        traceId = requireOptional(traceId, "traceId", 128);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    private static Optional<String> requireOptional(
            Optional<String> value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        return value.map(candidate -> requireText(candidate, name, maximumLength));
    }
}
