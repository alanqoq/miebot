package com.mieai.qqbot.gateway;

import java.util.Objects;

/** Sanitized session-layer failure with a stable reconnect category. */
public final class GatewaySessionException extends RuntimeException {
    private final GatewayReconnectCause category;

    public GatewaySessionException(
            GatewayReconnectCause category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category must not be null");
    }

    public GatewayReconnectCause category() {
        return category;
    }
}
