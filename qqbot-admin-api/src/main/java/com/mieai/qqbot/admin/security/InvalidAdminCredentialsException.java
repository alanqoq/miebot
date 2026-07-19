package com.mieai.qqbot.admin.security;

import java.io.Serial;

public final class InvalidAdminCredentialsException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidAdminCredentialsException() {
        super("The username or password is invalid");
    }
}
