package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.PluginLogger

/** Recording logger that never writes test messages to the process log. */
class FakePluginLogger : PluginLogger {
    private val entries = mutableListOf<Entry>()

    @Synchronized
    override fun info(message: String) {
        entries.add(Entry("INFO", message, null))
    }

    @Synchronized
    override fun warn(message: String) {
        entries.add(Entry("WARN", message, null))
    }

    @Synchronized
    override fun error(message: String, cause: Throwable) {
        entries.add(Entry("ERROR", message, cause))
    }

    @Synchronized
    fun entries(): List<Entry> = entries.toList()

    data class Entry(
        val level: String,
        val message: String,
        val cause: Throwable?,
    )
}
