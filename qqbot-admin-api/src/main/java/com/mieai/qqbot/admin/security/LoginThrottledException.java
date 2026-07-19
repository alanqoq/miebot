package com.mieai.qqbot.admin.security;

import java.io.Serial;

public final class LoginThrottledException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    LoginThrottledException(long retryAfterSeconds) {
        super("Too many failed sign-in attempts");
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
