package com.mieai.qqbot.runtime.configuration;

import java.util.Objects;

final class ConfigurationValidation {
    private ConfigurationValidation() {}

    static String displayName(String value) {
        Objects.requireNonNull(value, "displayName must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("displayName must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        return value;
    }
}
