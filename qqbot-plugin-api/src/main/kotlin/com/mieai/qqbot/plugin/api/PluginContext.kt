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
    val configurationJson: String,
    val messageSender: MessageSender,
    val logger: PluginLogger,
    val storage: PluginStorage,
) {
    val dataDirectory: Path = dataDirectory.toAbsolutePath().normalize()

    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(configurationJson.isNotBlank()) { "configurationJson must not be blank" }
    }
}
