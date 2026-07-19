package com.mieai.qqbot.domain.bot;

import java.time.Instant;
import java.util.Objects;

/** Persisted bot configuration without credentials or runtime connection state. */
public record BotDefinition(
        BotId id,
        String displayName,
        QqAppId appId,
        BotEnvironment environment,
        GatewayIntents intents,
        ShardSpec shardSpec,
        boolean enabled,
        BotRevision revision,
        Instant createdAt,
        Instant updatedAt) {

    public BotDefinition {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        Objects.requireNonNull(appId, "appId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        Objects.requireNonNull(revision, "revision must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (!displayName.equals(displayName.strip())) {
            throw new IllegalArgumentException("displayName must not have surrounding whitespace");
        }
        if (displayName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not be before createdAt");
        }
    }
}
