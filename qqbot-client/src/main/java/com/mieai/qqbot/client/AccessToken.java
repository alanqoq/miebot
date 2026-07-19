package com.mieai.qqbot.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Short-lived QQ OpenAPI credential. String rendering is always redacted. */
public final class AccessToken {
    private final String value;
    private final Instant expiresAt;

    private AccessToken(String value, Instant expiresAt) {
        this.value = validateValue(value);
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }

    public static AccessToken of(String value, Instant expiresAt) {
        return new AccessToken(value, expiresAt);
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean isUsableAt(Instant instant, Duration refreshSkew) {
        Objects.requireNonNull(instant, "instant must not be null");
        Objects.requireNonNull(refreshSkew, "refreshSkew must not be null");
        if (refreshSkew.isNegative()) {
            throw new IllegalArgumentException("refreshSkew must not be negative");
        }
        return Duration.between(instant, expiresAt).compareTo(refreshSkew) > 0;
    }

    /**
     * Returns the complete QQ authorization value for trusted transport implementations.
     * The returned value is sensitive and must never be logged or exposed to plugins.
     */
    public String authorizationHeaderValue() {
        return "QQBot " + value;
    }

    @Override
    public String toString() {
        return "AccessToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }

    private static String validateValue(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("value must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("value must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("value must not contain control characters");
        }
        return value;
    }
}
