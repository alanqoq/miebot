package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotId;
import java.io.Serial;
import java.util.Objects;

/** Raised when a requested bot configuration does not exist. */
public final class BotNotFoundException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final BotId botId;

    public BotNotFoundException(BotId botId) {
        super("Bot " + Objects.requireNonNull(botId, "botId must not be null") + " was not found");
        this.botId = botId;
    }

    public BotId botId() {
        return botId;
    }
}
