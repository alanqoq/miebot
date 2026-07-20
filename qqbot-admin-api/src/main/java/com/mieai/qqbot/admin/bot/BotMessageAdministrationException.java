package com.mieai.qqbot.admin.bot;

import org.springframework.http.HttpStatus;

public final class BotMessageAdministrationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    public BotMessageAdministrationException(HttpStatus status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
