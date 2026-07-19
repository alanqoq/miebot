package com.mieai.qqbot.domain.bot;

import java.util.Objects;
import java.util.UUID;

/** Stable internal identifier for a configured bot. */
public record BotId(UUID value) {
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    public BotId {
        Objects.requireNonNull(value, "value must not be null");
        if (NIL_UUID.equals(value)) {
            throw new IllegalArgumentException("value must not be the nil UUID");
        }
    }

    public static BotId of(UUID value) {
        return new BotId(value);
    }

    public static BotId parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("value must not have surrounding whitespace");
        }

        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("value must use the canonical UUID format");
            }
            return new BotId(parsed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("value must be a canonical UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
