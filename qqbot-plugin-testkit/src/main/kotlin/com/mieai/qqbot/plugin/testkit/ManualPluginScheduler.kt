package com.mieai.qqbot.plugin.testkit

import com.mieai.qqbot.plugin.api.PluginScheduler
import com.mieai.qqbot.plugin.api.PluginTask
import java.time.Duration

/** Scheduler whose tasks run only when the test explicitly advances it. */
class ManualPluginScheduler : PluginScheduler, AutoCloseable {
    private val tasks = mutableListOf<ScheduledTask>()

    @Synchronized
    override fun schedule(delay: Duration, task: () -> Unit): PluginTask = add(delay, null, task)

    @Synchronized
    override fun scheduleWithFixedDelay(initialDelay: Duration, delay: Duration, task: () -> Unit): PluginTask {
        requireDelay(delay, "delay", false)
        return add(initialDelay, delay, task)
    }

    @Synchronized
    fun runReady() {
        val ready = tasks.filter { !it.cancelled && it.remaining.isZero }
        ready.forEach(ScheduledTask::run)
        tasks.removeIf { it.cancelled }
    }

    @Synchronized
    fun advance(elapsed: Duration) {
        requireDelay(elapsed, "elapsed", true)
        for (task in tasks) {
            if (!task.cancelled) {
                val remaining = task.remaining.minus(elapsed)
                task.remaining = if (remaining.isNegative) Duration.ZERO else remaining
            }
        }
        runReady()
    }

    @Synchronized
    override fun close() {
        tasks.forEach(ScheduledTask::cancel)
        tasks.clear()
    }

    private fun add(initial: Duration, repeat: Duration?, callback: () -> Unit): ScheduledTask {
        requireDelay(initial, "initial delay", true)
        val task = ScheduledTask(initial, repeat, callback)
        tasks.add(task)
        return task
    }

    private fun requireDelay(value: Duration, name: String, allowZero: Boolean) {
        if (value.isNegative || (!allowZero && value.isZero)) {
            throw IllegalArgumentException("$name is invalid")
        }
    }

    private inner class ScheduledTask(
        var remaining: Duration,
        private val repeat: Duration?,
        private val callback: () -> Unit,
    ) : PluginTask {
        var cancelled = false

        fun run() {
            callback()
            if (repeat == null) {
                cancel()
            } else {
                remaining = repeat
            }
        }

        override fun cancel(): Boolean {
            val changed = !cancelled
            cancelled = true
            return changed
        }

        override val isCancelled: Boolean
            get() = cancelled
    }
}
