package com.mieai.qqbot.plugin.api

/** Minimal logger capability intentionally free of SLF4J/Spring types. */
interface PluginLogger {
    fun info(message: String)

    fun warn(message: String)

    fun error(message: String, cause: Throwable)
}
