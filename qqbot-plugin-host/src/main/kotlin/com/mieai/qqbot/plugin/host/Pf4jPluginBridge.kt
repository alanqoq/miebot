package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.plugin.spi.BotPluginFactory
import java.util.ServiceLoader
import org.pf4j.Plugin

/** PF4J lifecycle adapter; plugin authors only implement the Kotlin SPI service. */
class Pf4jPluginBridge(
    private val pluginClassLoader: ClassLoader,
) : Plugin() {
    private var factory: BotPluginFactory? = null

    override fun start() {
        val factories = ServiceLoader.load(BotPluginFactory::class.java, pluginClassLoader)
            .stream()
            .filter { provider -> provider.type().classLoader === pluginClassLoader }
            .map(ServiceLoader.Provider<BotPluginFactory>::get)
            .toList()
        if (factories.size != 1) {
            throw IllegalStateException("Plugin must provide exactly one BotPluginFactory service")
        }
        factory = factories.first()
    }

    override fun stop() {
        factory = null
    }

    val pluginFactory: BotPluginFactory
        get() = factory ?: throw IllegalStateException("Plugin has not been started")
}
