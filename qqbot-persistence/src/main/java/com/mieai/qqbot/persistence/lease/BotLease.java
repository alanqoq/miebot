package com.mieai.qqbot.persistence.lease;

import com.mieai.qqbot.domain.bot.BotId;
import java.time.Instant;
import java.util.Objects;

public record BotLease(BotId botId, int shardIndex, String ownerId, Instant leaseUntil, long fencingToken) {
    public BotLease {
        Objects.requireNonNull(botId, "botId must not be null");
        if (shardIndex < 0) throw new IllegalArgumentException("shardIndex must not be negative");
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255
                || ownerId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ownerId is invalid");
        }
        Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
        if (fencingToken < 1) throw new IllegalArgumentException("fencingToken must be positive");
    }
}
