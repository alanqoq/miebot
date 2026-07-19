package com.mieai.qqbot.persistence.admin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted administrator identity. The password hash is redacted from diagnostics. */
public final class AdminUser {
    private final UUID id;
    private final String username;
    private final String passwordHash;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant updatedAt;

    public AdminUser(
            UUID id,
            String username,
            String passwordHash,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.username = requireText(username, "username");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    public UUID id() {
        return id;
    }

    public String username() {
        return username;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public boolean enabled() {
        return enabled;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "AdminUser[id=" + id
                + ", username=" + username
                + ", passwordHash=<redacted>, enabled=" + enabled
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt + ']';
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
