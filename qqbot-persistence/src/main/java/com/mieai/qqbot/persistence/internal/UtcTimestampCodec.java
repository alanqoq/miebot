package com.mieai.qqbot.persistence.internal;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Objects;

/** Internal fixed-width timestamp codec whose output can be ordered lexicographically by SQLite. */
public final class UtcTimestampCodec {
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
            .appendInstant(9)
            .toFormatter(Locale.ROOT);

    private UtcTimestampCodec() {
    }

    public static String format(Instant value) {
        return FORMATTER.format(Objects.requireNonNull(value, "value must not be null"));
    }

    public static Instant parse(String value) {
        Objects.requireNonNull(value, "value must not be null");
        return Instant.from(FORMATTER.parse(value));
    }
}
