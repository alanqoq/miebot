package com.mieai.qqbot.admin.security;

import java.io.Serial;

public final class AdminAlreadyConfiguredException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public AdminAlreadyConfiguredException() {
        super("The administrator account has already been configured");
    }
}
