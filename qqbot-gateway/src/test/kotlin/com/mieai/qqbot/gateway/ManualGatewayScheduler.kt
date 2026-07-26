package com.mieai.qqbot.gateway

import java.time.Duration
import java.util.PriorityQueue

internal class ManualGatewayScheduler : GatewayScheduler {
    private val tasks = PriorityQueue(compareBy<Task> { it.dueNanos }.thenBy { it.order })
    private var nowNanos = 0L
    private var nextOrder = 0L
    private var nextFailure: RuntimeException? = null

    override fun schedule(delay: Duration, task: () -> Unit): GatewayScheduler.Cancellable {
        nextFailure?.let {
            nextFailure = null
            throw it
        }
        return Task(Math.addExact(nowNanos, delay.toNanos()), nextOrder++, task).also(tasks::add)
    }

    fun failNextSchedule() {
        nextFailure = IllegalStateException("test scheduler rejection")
    }

    fun advance(duration: Duration) {
        val target = Math.addExact(nowNanos, duration.toNanos())
        while (tasks.peek()?.dueNanos?.let { it <= target } == true) {
            val task = tasks.remove()
            nowNanos = task.dueNanos
            if (!task.cancelled) task.action()
        }
        nowNanos = target
    }

    private class Task(
        val dueNanos: Long,
        val order: Long,
        val action: () -> Unit,
        var cancelled: Boolean = false,
    ) : GatewayScheduler.Cancellable {
        override fun cancel() {
            cancelled = true
        }
    }
}
