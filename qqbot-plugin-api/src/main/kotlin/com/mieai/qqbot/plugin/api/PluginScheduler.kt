package com.mieai.qqbot.plugin.api

import java.time.Duration

/** Binding-scoped scheduler; callbacks run through the binding's bounded executor. */
interface PluginScheduler {
    fun schedule(delay: Duration, task: () -> Unit): PluginTask

    fun scheduleWithFixedDelay(initialDelay: Duration, delay: Duration, task: () -> Unit): PluginTask

    companion object {
        fun denied(): PluginScheduler = object : PluginScheduler {
            override fun schedule(delay: Duration, task: () -> Unit): PluginTask {
                throw SecurityException("Plugin scheduler capability is not granted")
            }

            override fun scheduleWithFixedDelay(
                initialDelay: Duration,
                delay: Duration,
                task: () -> Unit,
            ): PluginTask {
                throw SecurityException("Plugin scheduler capability is not granted")
            }
        }
    }
}
