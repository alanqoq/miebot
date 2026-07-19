package com.mieai.qqbot.admin.onboarding;

import org.springframework.http.HttpStatus;

public final class OnboardingOperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public OnboardingOperationException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
