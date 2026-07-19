package com.mieai.qqbot.runtime.security;

import java.io.Serial;

/** Raised when an AppSecret cannot be encrypted. */
public final class SecretEncryptionException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    SecretEncryptionException(Throwable cause) {
        super("Unable to encrypt AppSecret", cause);
    }
}
