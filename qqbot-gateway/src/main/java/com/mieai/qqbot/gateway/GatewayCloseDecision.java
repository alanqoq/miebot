package com.mieai.qqbot.gateway;

import java.util.Objects;

/** Classified action for a WebSocket close. */
public record GatewayCloseDecision(
        int statusCode,
        GatewayCloseDisposition disposition,
        GatewayReconnectCause reconnectCause) {
    public GatewayCloseDecision {
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(reconnectCause, "reconnectCause must not be null");
    }
}
