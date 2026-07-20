package com.mieai.qqbot.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record QqEmbedMessageRequest(QqMessageTargetType targetType, String targetId, String title,
        String prompt, List<Map<String, Object>> fields, Optional<String> replyMessageId,
        Optional<String> replyEventId, int messageSequence) {
    public QqEmbedMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is invalid");
        if (title == null || title.isBlank() || title.length() > 256) throw new IllegalArgumentException("title is invalid");
        if (prompt != null && prompt.length() > 512) throw new IllegalArgumentException("prompt is too long");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields must not be null")).stream().map(Map::copyOf).toList();
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }
}
