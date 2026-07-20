package com.mieai.qqbot.client;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record QqMarkdownMessageRequest(QqMessageTargetType targetType, String targetId, String content,
        Optional<String> customTemplateId, Map<String, String> params, Optional<String> replyMessageId,
        Optional<String> replyEventId, int messageSequence) {
    public QqMarkdownMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is invalid");
        if (content == null || content.isBlank() || content.length() > 4000) throw new IllegalArgumentException("content is invalid");
        Objects.requireNonNull(customTemplateId, "customTemplateId must not be null");
        params = Map.copyOf(Objects.requireNonNull(params, "params must not be null"));
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }
}
