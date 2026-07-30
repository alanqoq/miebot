package com.mieai.qqbot.plugin.api

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import java.nio.file.Path

/** Capabilities and immutable configuration for one plugin/bot binding. */
class PluginContext(
    val botId: BotId,
    val environment: BotEnvironment,
    val pluginId: String,
    dataDirectory: Path,
    @Deprecated("Use configurationContent; this accessor is retained for plugin API 3.1 compatibility")
    val configurationJson: String,
    val messageSender: MessageSender,
    val logger: PluginLogger,
    val storage: PluginStorage,
) {
    val dataDirectory: Path = dataDirectory.toAbsolutePath().normalize()

    /** Configuration text exactly as supplied by the host. */
    @Suppress("DEPRECATION")
    val configurationContent: String
        get() = configurationJson

    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        @Suppress("DEPRECATION")
        require(configurationJson.isNotBlank()) { "configurationJson must not be blank" }
    }
}
