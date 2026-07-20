package com.mieai.qqbot.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record QqArkMessageRequest(QqMessageTargetType targetType, String targetId, int templateId,
        List<Map<String, String>> values, Optional<String> replyMessageId, Optional<String> replyEventId,
        int messageSequence) {
    public QqArkMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is invalid");
        if (templateId < 1) throw new IllegalArgumentException("templateId must be positive");
        values = List.copyOf(Objects.requireNonNull(values, "values must not be null")).stream().map(Map::copyOf).toList();
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }
}
