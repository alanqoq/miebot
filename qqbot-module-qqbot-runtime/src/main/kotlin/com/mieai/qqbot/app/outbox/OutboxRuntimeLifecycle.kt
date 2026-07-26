package com.mieai.qqbot.app.outbox

import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle

class OutboxRuntimeLifecycle(
    private val worker: ProductionOutboxWorker,
    private val enabled: Boolean,
) : SmartLifecycle {
    private val running = AtomicBoolean()

    override fun start() {
        if (!enabled || !running.compareAndSet(false, true)) return
        try {
            worker.start()
        } catch (exception: RuntimeException) {
            running.set(false)
            LOGGER.warn("Outbox worker could not start ({})", exception.javaClass.simpleName)
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) worker.close()
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 600

    private companion object {
        val LOGGER = LoggerFactory.getLogger(OutboxRuntimeLifecycle::class.java)
    }
}
