package com.mieai.qqbot.plugin.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Text-only outbound command. The host persists it before any network call. */
public record TextMessage(
        MessageTarget target,
        String content,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence,
        Optional<String> deduplicationKey,
        Optional<UUID> sourceEventId) {
    public TextMessage {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank() || content.codePoints().anyMatch(TextMessage::unsupportedControl)) {
            throw new IllegalArgumentException("content must be non-blank and free of control characters");
        }
        if (content.codePointCount(0, content.length()) > 4000) {
            throw new IllegalArgumentException("content must not exceed 4000 characters");
        }
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

    private static boolean unsupportedControl(int value) {
        return Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t';
    }

    public static TextMessage reply(PluginEvent event, String content) {
        Objects.requireNonNull(event, "event must not be null");
        InboundMessage inbound = event.message().orElseThrow(
                () -> new IllegalArgumentException("event has no reply target"));
        String key = "reply:" + event.id() + ":" + Integer.toHexString(content.hashCode());
        return new TextMessage(inbound.replyTarget(), content, inbound.messageId(), inbound.eventId(), 1,
                Optional.of(key), Optional.of(event.id()));
    }
}
