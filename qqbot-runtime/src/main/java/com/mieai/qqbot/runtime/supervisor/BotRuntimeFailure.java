package com.mieai.qqbot.runtime.supervisor;

import java.util.Objects;

/** Sanitized runtime failure safe to expose through an administration API. */
public record BotRuntimeFailure(String code, String message, boolean retryable) {
    private static final int MAX_CODE_LENGTH = 128;
    private static final int MAX_MESSAGE_LENGTH = 1024;

    public BotRuntimeFailure {
        code = requireText(code, "code", MAX_CODE_LENGTH);
        message = requireText(message, "message", MAX_MESSAGE_LENGTH);
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }
}
