package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Metadata for a bounded local media asset staged for a bot-scoped send. */
public record MediaAsset(UUID id, BotId botId, QqMediaKind kind, String fileName,
        String contentType, long sizeBytes, Instant createdAt) {
    public MediaAsset {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (fileName == null || fileName.isBlank() || fileName.length() > 255) throw new IllegalArgumentException("fileName is invalid");
        if (contentType == null || contentType.isBlank() || contentType.length() > 128) throw new IllegalArgumentException("contentType is invalid");
        if (sizeBytes < 1L) throw new IllegalArgumentException("sizeBytes must be positive");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
