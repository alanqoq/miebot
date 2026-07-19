package com.mieai.qqbot.gateway;

import java.util.Objects;

/** Persistable subset required to resume a Gateway session. */
public record GatewaySessionSnapshot(String sessionId, long sequence) {
    public GatewaySessionSnapshot {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
    }

    public GatewaySessionSnapshot withSequence(long nextSequence) {
        if (nextSequence < sequence) {
            return this;
        }
        return nextSequence == sequence ? this : new GatewaySessionSnapshot(sessionId, nextSequence);
    }
}
