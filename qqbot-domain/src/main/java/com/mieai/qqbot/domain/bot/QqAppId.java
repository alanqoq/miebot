package com.mieai.qqbot.domain.bot;

import java.util.Objects;

/** Identifier assigned to a bot application by QQ. */
public record QqAppId(String value) {
    public QqAppId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("value must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("value must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("value must not contain control characters");
        }
    }

    public static QqAppId of(String value) {
        return new QqAppId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
