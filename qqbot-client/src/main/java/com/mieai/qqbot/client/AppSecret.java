package com.mieai.qqbot.client;

import java.util.Arrays;
import java.util.Objects;

/** Secret assigned to a QQ bot application. String rendering is always redacted. */
public final class AppSecret implements AutoCloseable {
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

    synchronized String rawValue() {
        if (value == null) {
            throw new IllegalStateException("AppSecret has been destroyed");
        }
        return new String(value);
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
        if (Character.isWhitespace(value[0]) || Character.isWhitespace(value[value.length - 1])) {
            throw new IllegalArgumentException("value must not have surrounding whitespace");
        }
        for (char character : value) {
            if (Character.isWhitespace(character)) {
                throw new IllegalArgumentException("value must not contain whitespace");
            }
            if (Character.isISOControl(character)) {
                throw new IllegalArgumentException("value must not contain control characters");
            }
        }
    }
}
