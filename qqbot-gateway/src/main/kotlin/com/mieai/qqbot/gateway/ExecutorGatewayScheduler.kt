package com.mieai.qqbot.gateway

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Single daemon-thread scheduler suitable for one or more Gateway sessions. */
class ExecutorGatewayScheduler(threadName: String) : GatewayScheduler, AutoCloseable {
    private val executor: ScheduledExecutorService

    init {
        require(threadName.isNotBlank()) { "threadName must not be blank" }
        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, threadName).apply { isDaemon = true }
        }
    }

    override fun schedule(delay: Duration, task: () -> Unit): GatewayScheduler.Cancellable {
        require(!delay.isNegative) { "delay must not be negative" }
        val future = executor.schedule(Runnable { task() }, delay.toNanos(), TimeUnit.NANOSECONDS)
        return GatewayScheduler.Cancellable { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
