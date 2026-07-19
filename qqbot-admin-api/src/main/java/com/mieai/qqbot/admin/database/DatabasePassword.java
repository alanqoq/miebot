package com.mieai.qqbot.admin.database;

import java.util.Arrays;
import java.util.Objects;

/** Mutable database credential whose diagnostic representation never exposes its value. */
public final class DatabasePassword implements AutoCloseable {
    private char[] value;

    private DatabasePassword(char[] value) {
        this.value = value.clone();
    }

    public static DatabasePassword of(String value) {
        Objects.requireNonNull(value, "value must not be null");
        char[] characters = value.toCharArray();
        try {
            return new DatabasePassword(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    public synchronized char[] copyValue() {
        if (value == null) {
            throw new IllegalStateException("Database password has been destroyed");
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
        return "DatabasePassword[<redacted>]";
    }
}
