package com.mieai.qqbot.module.api;

final class ModuleValidation {
    private ModuleValidation() {}

    static String requireId(String value, String name) {
        String text = requireText(value, name, 128);
        if (!text.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return text;
    }

    static String requireVersion(String value, String name) {
        String text = requireText(value, name, 64);
        if (!text.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)(?:[-+][0-9A-Za-z.-]+)?")) {
            throw new IllegalArgumentException(name + " is not a semantic version");
        }
        return text;
    }

    static String requireText(String value, String name, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String text = value.strip();
        if (text.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return text;
    }
}
