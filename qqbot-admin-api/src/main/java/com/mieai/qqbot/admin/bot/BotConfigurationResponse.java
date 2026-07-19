package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.runtime.configuration.BotConfigurationView;
import java.time.Instant;
import java.util.UUID;

public record BotConfigurationResponse(
        UUID id,
        String displayName,
        String appId,
        String environment,
        long intents,
        int shardIndex,
        int shardCount,
        boolean enabled,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        boolean secretConfigured) {

    static BotConfigurationResponse from(BotConfigurationView view) {
        return new BotConfigurationResponse(
                view.id().value(),
                view.displayName(),
                view.appId().value(),
                view.environment().name(),
                view.intents().bits(),
                view.shardSpec().index(),
                view.shardSpec().count(),
                view.enabled(),
                view.revision().value(),
                view.createdAt(),
                view.updatedAt(),
                view.secretConfigured());
    }
}
