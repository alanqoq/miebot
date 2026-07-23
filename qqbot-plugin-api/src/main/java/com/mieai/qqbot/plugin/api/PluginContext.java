package com.mieai.qqbot.plugin.api;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.nio.file.Path;
import java.util.Objects;

/** Capabilities and immutable configuration for one plugin/bot binding. */
public record PluginContext(
        BotId botId,
        BotEnvironment environment,
        String pluginId,
        Path dataDirectory,
        String configurationJson,
        MessageSender messageSender,
        PluginLogger logger,
        PluginStorage storage) {
    public PluginContext {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        if (pluginId == null || pluginId.isBlank()) throw new IllegalArgumentException("pluginId must not be blank");
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory must not be null")
                .toAbsolutePath().normalize();
        if (configurationJson == null || configurationJson.isBlank()) throw new IllegalArgumentException("configurationJson must not be blank");
        Objects.requireNonNull(messageSender, "messageSender must not be null");
        Objects.requireNonNull(logger, "logger must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
    }
}
