package com.mieai.qqbot.persistence.plugin;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import java.time.Instant;
import java.util.Objects;

/** Durable metadata for one operator-installed plugin artifact. */
public record PluginArtifact(
        String pluginId,
        String name,
        String version,
        String apiCompatibility,
        String fileName,
        String sha256,
        String entrypoint,
        String status,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
    public PluginArtifact {
        requireToken(pluginId, "pluginId", 128);
        requireText(name, "name", 255);
        requireToken(version, "version", 128);
        requireText(apiCompatibility, "apiCompatibility", 128);
        requireText(fileName, "fileName", 255);
        requireToken(sha256, "sha256", 128);
        requireToken(entrypoint, "entrypoint", 255);
        requireToken(status, "status", 32);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }

    private static void requireText(String value, String name, int maximum) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)
                || value.codePointCount(0, value.length()) > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
