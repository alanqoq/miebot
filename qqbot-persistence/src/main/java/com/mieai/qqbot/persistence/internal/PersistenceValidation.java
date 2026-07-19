package com.mieai.qqbot.persistence.internal;

import java.util.Objects;
import java.util.UUID;

/** Shared validation for persistence input values that become keys or discriminator columns. */
public final class PersistenceValidation {
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    private PersistenceValidation() {
    }

    public static UUID requireIdentifier(UUID value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (NIL_UUID.equals(value)) {
            throw new IllegalArgumentException(name + " must not be the nil UUID");
        }
        return value;
    }

    public static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }

    public static String requireToken(String value, String name, int maxCharacters) {
        String normalized = requireToken(value, name);
        if (normalized.codePointCount(0, normalized.length()) > maxCharacters) {
            throw new IllegalArgumentException(
                    name + " must not exceed " + maxCharacters + " characters");
        }
        return normalized;
    }

    public static String requirePayload(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
