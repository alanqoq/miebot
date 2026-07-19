package com.mieai.qqbot.persistence.bot;

import java.util.Objects;

/** Opaque, versioned ciphertext envelope and the key identifier needed to decrypt it. */
public final class SecretCiphertext {
    private final String ciphertext;
    private final String keyId;

    public SecretCiphertext(String ciphertext, String keyId) {
        this.ciphertext = requireToken(ciphertext, "ciphertext");
        this.keyId = requireToken(keyId, "keyId");
    }

    public static SecretCiphertext of(String ciphertext, String keyId) {
        return new SecretCiphertext(ciphertext, keyId);
    }

    public String ciphertext() {
        return ciphertext;
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SecretCiphertext that)) {
            return false;
        }
        return ciphertext.equals(that.ciphertext) && keyId.equals(that.keyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ciphertext, keyId);
    }

    @Override
    public String toString() {
        return "SecretCiphertext[keyId=" + keyId + ", ciphertext=<redacted>]";
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must not have surrounding whitespace");
        }
        if (value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must not contain whitespace");
        }
        if (value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }
}
