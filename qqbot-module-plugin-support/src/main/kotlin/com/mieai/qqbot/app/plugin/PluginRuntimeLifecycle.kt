package com.mieai.qqbot.app.plugin

import com.mieai.qqbot.admin.plugins.PluginBindingFileService
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean

class PluginRuntimeLifecycle(
    private val runtime: PluginRuntimeService,
    private val host: Pf4jPluginHost,
    private val files: PluginBindingFileService,
    private val enabled: Boolean,
) : SmartLifecycle {
    private val running = AtomicBoolean()

    override fun start() {
        if (!enabled || !running.compareAndSet(false, true)) return
        try {
            host.start()
            files.initializeMissingBindings()
            runtime.start()
        } catch (exception: RuntimeException) {
            running.set(false)
            LOGGER.warn("Plugin runtime could not start ({})", exception.javaClass.simpleName)
        }
    }

    override fun stop() {
        if (running.compareAndSet(true, false)) runtime.close()
    }

    override fun stop(callback: Runnable) {
        stop()
        callback.run()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MAX_VALUE - 1_200

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PluginRuntimeLifecycle::class.java)
    }
}
