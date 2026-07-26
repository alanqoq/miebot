package com.mieai.qqbot.plugin.spi

import com.mieai.qqbot.plugin.api.PluginRuntimeContext

/** ServiceLoader entry point supplied by a trusted plugin JAR. */
interface BotPluginFactory {
    val pluginId: String

    fun create(context: PluginRuntimeContext): BotPlugin
}
