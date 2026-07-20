package com.mieai.qqbot.client;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Extensible rich-message envelope shared by all supported QQ message targets. */
public record QqRichMessageRequest(
        QqMessageTargetType targetType,
        String targetId,
        QqRichMessageKind kind,
        Map<String, Object> payload,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence) {
    public QqRichMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        if (targetId == null || targetId.isBlank() || !targetId.equals(targetId.strip())
                || targetId.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("targetId is invalid");
        }
        Objects.requireNonNull(kind, "kind must not be null");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        if (payload.isEmpty()) throw new IllegalArgumentException("payload must not be empty");
        if (kind == QqRichMessageKind.KEYBOARD) validateKeyboardPayload(payload);
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }

    private static void validateKeyboardPayload(Map<String, Object> value) {
        if (!value.keySet().equals(java.util.Set.of("markdown", "keyboard"))
                || !(value.get("markdown") instanceof Map<?, ?> markdown)
                || !(value.get("keyboard") instanceof Map<?, ?> keyboard)
                || keyboard.isEmpty()) {
            throw new IllegalArgumentException("keyboard payload must contain markdown and keyboard objects");
        }
        Object content = markdown.get("content");
        Object template = markdown.get("custom_template_id");
        boolean hasContent = content instanceof String text && !text.isBlank();
        boolean hasTemplate = template instanceof String text && !text.isBlank();
        if (!hasContent && !hasTemplate) {
            throw new IllegalArgumentException("keyboard markdown must contain content or custom_template_id");
        }
    }
}
