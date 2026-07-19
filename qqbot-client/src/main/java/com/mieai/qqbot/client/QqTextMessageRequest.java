package com.mieai.qqbot.client;

import java.util.Objects;
import java.util.Optional;

/** Validated text request used by the QQ OpenAPI sender. */
public record QqTextMessageRequest(
        QqMessageTargetType targetType,
        String targetId,
        String content,
        Optional<String> replyMessageId,
        Optional<String> replyEventId,
        int messageSequence) {
    public QqTextMessageRequest {
        Objects.requireNonNull(targetType, "targetType must not be null");
        requirePathToken(targetId, "targetId");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank() || content.codePoints().anyMatch(QqTextMessageRequest::unsupportedControl)) {
            throw new IllegalArgumentException("content must be non-blank and free of control characters");
        }
        if (content.codePointCount(0, content.length()) > 4000) throw new IllegalArgumentException("content is too long");
        Objects.requireNonNull(replyMessageId, "replyMessageId must not be null");
        Objects.requireNonNull(replyEventId, "replyEventId must not be null");
        if (messageSequence < 1) throw new IllegalArgumentException("messageSequence must be positive");
    }

    private static boolean unsupportedControl(int value) {
        return Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t';
    }

    private static void requirePathToken(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
