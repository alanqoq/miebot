package com.mieai.qqbot.gateway

import java.time.Duration

/** Scheduling boundary used for heartbeats, ACK deadlines, and reconnect delays. */
fun interface GatewayScheduler {
    fun schedule(delay: Duration, task: () -> Unit): Cancellable

    fun interface Cancellable {
        fun cancel()
    }
}
