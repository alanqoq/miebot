package com.mieai.qqbot.persistence.bot;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotId;
import java.util.Objects;

/** Complete persisted bot configuration, including an opaque encrypted AppSecret. */
public record StoredBot(BotDefinition definition, SecretCiphertext appSecret) {
    public StoredBot {
        Objects.requireNonNull(definition, "definition must not be null");
        Objects.requireNonNull(appSecret, "appSecret must not be null");
    }

    public BotId id() {
        return definition.id();
    }
}
