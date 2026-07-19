package com.mieai.qqbot.runtime.supervisor;

import java.util.Objects;

/** Latest resumable Gateway session identity and sequence safe for runtime diagnostics. */
public record BotSessionSnapshot(String sessionId, long sequence) {
    public BotSessionSnapshot {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (!sessionId.equals(sessionId.strip())) {
            throw new IllegalArgumentException("sessionId must not have surrounding whitespace");
        }
        if (sessionId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sessionId must not contain control characters");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    public BotSessionSnapshot withSequence(long nextSequence) {
        if (nextSequence < sequence) {
            throw new IllegalArgumentException("nextSequence must not move backwards");
        }
        return nextSequence == sequence ? this : new BotSessionSnapshot(sessionId, nextSequence);
    }
}
