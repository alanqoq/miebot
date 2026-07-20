package com.mieai.qqbot.plugin.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record StagedMediaMessage(
        MessageTarget target,
        StagedMedia media,
        Optional<String> content,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence,
        Optional<String> deduplicationKey,
        Optional<UUID> sourceEventId) {
    public StagedMediaMessage {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(media, "media must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
    }
}
