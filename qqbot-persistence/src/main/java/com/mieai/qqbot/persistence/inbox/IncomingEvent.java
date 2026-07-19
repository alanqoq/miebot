package com.mieai.qqbot.persistence.inbox;

import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireIdentifier;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requirePayload;
import static com.mieai.qqbot.persistence.internal.PersistenceValidation.requireToken;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Platform event ready to be persisted before any business processing begins. */
public record IncomingEvent(
        UUID id,
        BotEnvironment environment,
        BotId botId,
        String eventType,
        String platformEventId,
        String payload,
        Instant receivedAt) {

    public IncomingEvent {
        requireIdentifier(id, "id");
        Objects.requireNonNull(environment, "environment must not be null");
        Objects.requireNonNull(botId, "botId must not be null");
        requireToken(eventType, "eventType", 128);
        requireToken(platformEventId, "platformEventId", 255);
        requirePayload(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt must not be null");
    }
}
