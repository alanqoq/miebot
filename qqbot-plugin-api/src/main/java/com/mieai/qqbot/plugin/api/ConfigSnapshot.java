package com.mieai.qqbot.plugin.api;

import java.time.Instant;
import java.util.Objects;

/** Immutable binding configuration captured when a plugin instance is created. */
public record ConfigSnapshot(String json, long revision, Instant loadedAt) {
    public ConfigSnapshot {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("json must not be blank");
        if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
        Objects.requireNonNull(loadedAt, "loadedAt must not be null");
    }
}
