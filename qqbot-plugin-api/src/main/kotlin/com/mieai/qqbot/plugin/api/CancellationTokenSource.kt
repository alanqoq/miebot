package com.mieai.qqbot.plugin.api

import java.util.concurrent.atomic.AtomicBoolean

/** Host/plugin-owned source for a cooperative [CancellationToken]. */
class CancellationTokenSource {
    private val cancelled = AtomicBoolean()
    val token = CancellationToken(cancelled)

    fun cancel(): Boolean = cancelled.compareAndSet(false, true)
}
