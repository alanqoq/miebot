package com.mieai.qqbot.app.gateway

import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle

/** Starts bot reconciliation after infrastructure and stops it before dependencies. */
class BotSupervisorLifecycle(
    private val supervisor: BotSupervisor,
    private val enabled: Boolean,
) : SmartLifecycle {
    private val running = AtomicBoolean()

    override fun start() {
        if (!enabled || !running.compareAndSet(false, true)) return
        try {
            supervisor.start().whenComplete { _, failure ->
                if (failure != null) {
                    LOGGER.warn(
                        "Initial bot runtime reconciliation failed ({})",
                        failure.javaClass.simpleName,
                    )
                }
            }
        } catch (exception: RuntimeException) {
            running.set(false)
            throw exception
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) supervisor.close()
    }

    override fun stop(callback: Runnable) {
        if (!running.compareAndSet(true, false)) {
            callback.run()
            return
        }
        try {
            supervisor.shutdown().whenComplete { _, _ -> callback.run() }
        } catch (exception: RuntimeException) {
            LOGGER.warn(
                "Bot runtime shutdown could not be started ({})",
                exception.javaClass.simpleName,
            )
            callback.run()
        }
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = PHASE

    private companion object {
        val LOGGER = LoggerFactory.getLogger(BotSupervisorLifecycle::class.java)
        const val PHASE = Int.MAX_VALUE - 1_000
    }
}
