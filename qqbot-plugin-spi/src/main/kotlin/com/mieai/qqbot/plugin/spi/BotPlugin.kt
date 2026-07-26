package com.mieai.qqbot.plugin.spi

/** Pure Kotlin/JVM plugin lifecycle contract. */
interface BotPlugin {
    fun start()

    fun stop()
}
