package com.mieai.qqbot.admin.database;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public final class DatabaseAdministrationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public DatabaseAdministrationException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DatabaseAdministrationException(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fieldErrors) {
        super(message);
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.code = requireText(code, "code");
        this.fieldErrors = Map.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
