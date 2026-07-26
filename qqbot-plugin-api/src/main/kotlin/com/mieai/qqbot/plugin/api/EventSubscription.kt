package com.mieai.qqbot.plugin.api

/** A binding-scoped event subscription removed automatically when the plugin stops. */
interface EventSubscription : AutoCloseable {
    val handlerId: String

    val isActive: Boolean

    override fun close()
}
