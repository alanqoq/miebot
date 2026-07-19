package com.mieai.qqbot.runtime.security;

import java.io.Serial;

/** Signals that AppSecret cryptographic work cannot proceed until a master key is configured. */
public final class KeyUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public KeyUnavailableException() {
        super("AppSecret master key is unavailable");
    }
}
