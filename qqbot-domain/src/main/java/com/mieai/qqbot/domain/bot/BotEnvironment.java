package com.mieai.qqbot.domain.bot;

import java.util.Locale;
import java.util.Objects;

/** QQ environment whose sessions and target identifiers must remain isolated. */
public enum BotEnvironment {
    SANDBOX,
    PRODUCTION;

    public static BotEnvironment parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }

        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported bot environment: " + value, exception);
        }
    }

    public boolean isSandbox() {
        return this == SANDBOX;
    }
}
