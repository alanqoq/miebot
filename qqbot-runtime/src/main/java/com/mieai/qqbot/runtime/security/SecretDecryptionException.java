package com.mieai.qqbot.runtime.security;

import java.io.Serial;

/** Raised when an AppSecret envelope cannot be authenticated or decrypted. */
public final class SecretDecryptionException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    SecretDecryptionException(Throwable cause) {
        super("Unable to decrypt AppSecret", cause);
    }
}
