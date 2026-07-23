package com.mieai.qqbot.runtime.event;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.gateway.GatewayDispatch;
import java.time.Instant;
import java.util.Objects;

/** A durable-ingress Gateway dispatch made available to trusted read-only consumers. */
public record BotGatewayEvent(BotId botId, GatewayDispatch dispatch, Instant receivedAt) {
    public BotGatewayEvent {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(dispatch, "dispatch must not be null");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}
