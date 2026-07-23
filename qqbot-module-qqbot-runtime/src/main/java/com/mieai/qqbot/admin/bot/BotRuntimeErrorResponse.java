package com.mieai.qqbot.admin.bot;

import java.time.Instant;
import java.util.Objects;

public record BotRuntimeErrorResponse(String code, String message, Instant occurredAt) {

    public BotRuntimeErrorResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
