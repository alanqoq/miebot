package com.mieai.qqbot.persistence.bot;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import java.util.Objects;

/** Raised when a bot configuration was changed or removed after it was read. */
public final class OptimisticLockException extends RuntimeException {
    private final BotId botId;
    private final BotRevision expectedRevision;

    public OptimisticLockException(BotId botId, BotRevision expectedRevision) {
        super("Bot " + Objects.requireNonNull(botId, "botId must not be null")
                + " does not have expected revision "
                + Objects.requireNonNull(expectedRevision, "expectedRevision must not be null").value());
        this.botId = botId;
        this.expectedRevision = expectedRevision;
    }

    public BotId botId() {
        return botId;
    }

    public BotRevision expectedRevision() {
        return expectedRevision;
    }
}
