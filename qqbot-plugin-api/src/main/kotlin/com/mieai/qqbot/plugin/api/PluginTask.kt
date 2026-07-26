package com.mieai.qqbot.plugin.api

/** A host-owned scheduled task that is cancelled when its binding stops. */
interface PluginTask : AutoCloseable {
    fun cancel(): Boolean

    val isCancelled: Boolean

    override fun close() {
        cancel()
    }
}
