package com.mieai.qqbot.runtime.security;

import java.util.Arrays;
import java.util.Objects;

/** Mutable-in-memory AppSecret value whose string representation is always redacted. */
public final class AppSecret implements AutoCloseable {
    private static final int MAX_CHARACTERS = 4096;

    private char[] value;

    private AppSecret(char[] value) {
        validate(value);
        this.value = value.clone();
    }

    public static AppSecret of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        char[] characters = value.toCharArray();
        try {
            return new AppSecret(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    public static AppSecret of(char[] value) {
        Objects.requireNonNull(value, "value must not be null");
        return new AppSecret(value);
    }

    synchronized char[] copyValue() {
        if (value == null) {
            throw new IllegalStateException("AppSecret has been destroyed");
        }
        return value.clone();
    }

    public synchronized boolean isDestroyed() {
        return value == null;
    }

    @Override
    public synchronized void close() {
        if (value != null) {
            Arrays.fill(value, '\0');
            value = null;
        }
    }

    @Override
    public String toString() {
        return "AppSecret[<redacted>]";
    }

    private static void validate(char[] value) {
        if (value.length == 0) {
            throw new IllegalArgumentException("value must not be empty");
        }
        if (value.length > MAX_CHARACTERS) {
            throw new IllegalArgumentException("value is too long");
        }
        if (Character.isWhitespace(value[0]) || Character.isWhitespace(value[value.length - 1])) {
            throw new IllegalArgumentException("value must not have surrounding whitespace");
        }
        for (int index = 0; index < value.length; index++) {
            char character = value[index];
            if (Character.isWhitespace(character)) {
                throw new IllegalArgumentException("value must not contain whitespace");
            }
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("value must not contain control characters");
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) {
                    throw new IllegalArgumentException("value must contain valid Unicode");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("value must contain valid Unicode");
            }
        }
    }
}
