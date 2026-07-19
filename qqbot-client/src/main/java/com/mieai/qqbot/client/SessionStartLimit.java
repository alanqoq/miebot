package com.mieai.qqbot.client;

import java.time.Duration;
import java.util.Objects;

/** Session creation budget returned by {@code GET /gateway/bot}. */
public record SessionStartLimit(
        int total, int remaining, Duration resetAfter, int maxConcurrency) {
    public SessionStartLimit {
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (remaining < 0 || remaining > total) {
            throw new IllegalArgumentException("remaining must be between zero and total");
        }
        Objects.requireNonNull(resetAfter, "resetAfter must not be null");
        if (resetAfter.isNegative()) {
            throw new IllegalArgumentException("resetAfter must not be negative");
        }
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be positive");
        }
    }
}
