package com.mieai.qqbot.plugin.host

import java.util.concurrent.atomic.AtomicBoolean

/** Fences host capabilities after a binding is stopped, paused, or quarantined. */
class BindingCapabilityGuard : AutoCloseable {
    private val active = AtomicBoolean(true)

    fun requireActive() {
        if (!active.get()) {
            throw IllegalStateException("Plugin binding capabilities are fenced")
        }
    }

    fun isActive(): Boolean = active.get()

    override fun close() {
        active.set(false)
    }
}
