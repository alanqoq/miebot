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
        Instant updatedAt,
        long maxMediaUploadBytes) {

    public static final long DEFAULT_MAX_MEDIA_UPLOAD_BYTES = 16L * 1024L * 1024L;
    public static final long MIN_MAX_MEDIA_UPLOAD_BYTES = 1024L * 1024L;
    public static final long MAX_MAX_MEDIA_UPLOAD_BYTES = 256L * 1024L * 1024L;

    /** Binary/source-compatible constructor for callers compiled against platform 0.1.x. */
    public BotDefinition(
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
        this(id, displayName, appId, environment, intents, shardSpec, enabled, revision,
                createdAt, updatedAt, DEFAULT_MAX_MEDIA_UPLOAD_BYTES);
    }

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
        if (maxMediaUploadBytes < MIN_MAX_MEDIA_UPLOAD_BYTES
                || maxMediaUploadBytes > MAX_MAX_MEDIA_UPLOAD_BYTES) {
            throw new IllegalArgumentException("maxMediaUploadBytes must be between 1 MiB and 256 MiB");
        }
    }
}
