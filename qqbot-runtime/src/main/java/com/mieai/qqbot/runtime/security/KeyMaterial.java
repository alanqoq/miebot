package com.mieai.qqbot.runtime.security;

import java.util.Arrays;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Identified AES-256 master key supplied by a {@link KeyProvider}. */
public final class KeyMaterial {
    private static final int AES_256_BYTES = 32;

    private final String keyId;
    private final SecretKey key;

    public KeyMaterial(String keyId, SecretKey key) {
        this.keyId = requireKeyId(keyId);
        Objects.requireNonNull(key, "key must not be null");
        if (!"AES".equalsIgnoreCase(key.getAlgorithm())) {
            throw new IllegalArgumentException("key algorithm must be AES");
        }

        byte[] encoded = key.getEncoded();
        if (encoded == null) {
            throw new IllegalArgumentException("key must expose RAW key material");
        }
        try {
            if (encoded.length != AES_256_BYTES) {
                throw new IllegalArgumentException("key must contain exactly 256 bits");
            }
            this.key = new SecretKeySpec(encoded, "AES");
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    public String keyId() {
        return keyId;
    }

    SecretKey key() {
        return key;
    }

    @Override
    public String toString() {
        return "KeyMaterial[keyId=" + keyId + ", key=<redacted>]";
    }

    private static String requireKeyId(String value) {
        Objects.requireNonNull(value, "keyId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("keyId must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException("keyId must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("keyId must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("keyId must not contain control characters");
        }
        return value;
    }
}
