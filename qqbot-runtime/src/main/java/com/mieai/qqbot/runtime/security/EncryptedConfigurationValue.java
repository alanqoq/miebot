package com.mieai.qqbot.runtime.security;

import java.util.Objects;

/** Versioned encrypted value used for application configuration secrets. */
public record EncryptedConfigurationValue(String ciphertext, String keyId) {
    public EncryptedConfigurationValue {
        ciphertext = requireText(ciphertext, "ciphertext");
        keyId = requireText(keyId, "keyId");
    }

    @Override
    public String toString() {
        return "EncryptedConfigurationValue[ciphertext=<redacted>, keyId=" + keyId + "]";
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
