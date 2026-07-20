package com.mieai.qqbot.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record QqKeyboardMessageRequest(QqMessageTargetType targetType, String targetId,
        String markdownContent,
        Optional<String> keyboardId, List<Map<String, Object>> rows,
        Optional<String> replyMessageId, Optional<String> replyEventId, int messageSequence) {
    public QqKeyboardMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (targetId == null || targetId.isBlank()) throw new IllegalArgumentException("targetId is invalid");
        if (markdownContent == null || markdownContent.isBlank() || markdownContent.length() > 4000) {
            throw new IllegalArgumentException("markdownContent is invalid");
        }
        Objects.requireNonNull(keyboardId, "keyboardId must not be null");
        rows = rows == null ? List.of() : rows.stream().map(Map::copyOf).toList();
        if (keyboardId.isEmpty() && rows.isEmpty()) throw new IllegalArgumentException("keyboardId or rows is required");
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }
}
