package com.mieai.qqbot.plugin.api;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Structured rich-message command; payload keys follow the QQ OpenAPI object for its kind. */
public record RichMessage(
        MessageTarget target,
        RichMessageKind kind,
        Map<String, Object> payload,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence,
        Optional<String> deduplicationKey,
        Optional<UUID> sourceEventId) {
    public RichMessage {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload must not be null"));
        if (payload.isEmpty()) throw new IllegalArgumentException("payload must not be empty");
        if (kind == RichMessageKind.KEYBOARD) validateKeyboardPayload(payload);
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
        Objects.requireNonNull(deduplicationKey, "deduplicationKey must not be null");
        Objects.requireNonNull(sourceEventId, "sourceEventId must not be null");
        deduplicationKey.ifPresent(value -> {
            if (value.isBlank() || value.length() > 512 || value.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("deduplicationKey is invalid");
            }
        });
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
