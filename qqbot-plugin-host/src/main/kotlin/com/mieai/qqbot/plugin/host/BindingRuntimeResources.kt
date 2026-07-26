package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.plugin.api.CancellationToken
import com.mieai.qqbot.plugin.api.CancellationTokenSource
import com.mieai.qqbot.plugin.api.EventService
import com.mieai.qqbot.plugin.api.EventSubscription
import com.mieai.qqbot.plugin.api.PluginEvent
import com.mieai.qqbot.plugin.api.PluginEventHandler
import com.mieai.qqbot.plugin.api.PluginScheduler
import com.mieai.qqbot.plugin.api.PluginTask
import java.time.Duration
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** All executable resources owned by one plugin binding. */
class BindingRuntimeResources(pluginId: String, bindingId: String, queueCapacity: Int) : AutoCloseable {
    private val executor: ThreadPoolExecutor
    private val scheduler: ScheduledExecutorService
    internal val capabilityGuard = BindingCapabilityGuard()
    private val handlers = LinkedHashMap<String, Registration>()
    private val scheduled = ArrayList<ScheduledFuture<*>>()
    private val inFlight = AtomicInteger()
    private val idleMonitor = Object()
    private var closed = false

    init {
        require(queueCapacity >= 1) { "queueCapacity must be positive" }
        val suffix = "${sanitize(pluginId)}-${sanitize(bindingId)}"
        executor = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(queueCapacity),
            daemonFactory("qqbot-plugin-$suffix"),
            ThreadPoolExecutor.AbortPolicy(),
        )
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonFactory("qqbot-plugin-scheduler-$suffix"))
    }

    fun eventService(): EventService = EventService { handlerId, eventTypes, handler ->
        subscribe(handlerId, eventTypes.toSet(), handler)
    }

    fun pluginScheduler(): PluginScheduler = object : PluginScheduler {
        override fun schedule(delay: Duration, task: () -> Unit): PluginTask = scheduleTask(delay, null, task)

        override fun scheduleWithFixedDelay(initialDelay: Duration, delay: Duration, task: () -> Unit): PluginTask =
            scheduleTask(initialDelay, requireDelay(delay, false), task)
    }

    @Synchronized
    fun handlerIds(): List<String> = handlers.keys.toList()

    @Synchronized
    fun handlerIds(eventType: String): List<String> {
        return handlers.values.filter { it.matches(eventType) }.map { it.id }
    }

    fun execute(handlerId: String, event: PluginEvent): CompletionStage<Void> =
        executeCancellable(handlerId, event).stage()

    fun executeCancellable(handlerId: String, event: PluginEvent): PluginExecution {
        val selected: PluginEventHandler
        synchronized(this) {
            if (closed) return completedExecution(IllegalStateException("Plugin binding is stopped"))
            val registration = handlers[handlerId] ?: return completedExecution(null)
            if (!registration.matches(event.eventType)) return completedExecution(null)
            selected = registration.handler
        }
        inFlight.incrementAndGet()
        val invocation = Invocation(selected, event)
        try {
            invocation.submit()
        } catch (exception: RuntimeException) {
            invocation.finish(IllegalStateException("Plugin binding queue is full", exception))
        }
        return invocation
    }

    private fun completedExecution(failure: Throwable?): PluginExecution {
        val result = CompletableFuture<Void>()
        if (failure == null) result.complete(null) else result.completeExceptionally(failure)
        return object : PluginExecution {
            override fun stage(): CompletionStage<Void> = result.minimalCompletionStage()

            override fun cancel() = Unit

            override fun isDone(): Boolean = true

            override fun await(timeout: Duration): Boolean = true
        }
    }

    fun awaitIdle(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + requireDelay(timeout, true).toNanos()
        synchronized(idleMonitor) {
            while (inFlight.get() != 0) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                try {
                    TimeUnit.NANOSECONDS.timedWait(idleMonitor, remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    override fun close() {
        beginShutdown()
        executor.shutdownNow()
    }

    /** Stop accepting new callbacks before the host waits for in-flight work. */
    fun beginShutdown() {
        capabilityGuard.close()
        synchronized(this) {
            if (closed) return
            closed = true
            handlers.values.forEach(Registration::deactivate)
            handlers.clear()
            scheduled.forEach { it.cancel(false) }
            scheduled.clear()
        }
        scheduler.shutdownNow()
        // Let already accepted callbacks drain; the host applies its bounded
        // shutdown timeout and calls close() to interrupt anything remaining.
        executor.shutdown()
    }

    @Synchronized
    private fun subscribe(
        handlerId: String,
        eventTypes: Set<String>,
        handler: PluginEventHandler,
    ): EventSubscription {
        validateHandlerId(handlerId)
        if (closed) throw IllegalStateException("Plugin binding is stopped")
        val registration = Registration(
            handlerId,
            eventTypes.toSet(),
            handler,
        )
        if (handlers.putIfAbsent(handlerId, registration) != null) {
            throw IllegalArgumentException("handlerId is already registered")
        }
        return registration
    }

    @Synchronized
    private fun scheduleTask(initial: Duration, repeat: Duration?, callback: () -> Unit): PluginTask {
        val first = requireDelay(initial, true)
        if (closed) throw IllegalStateException("Plugin binding is stopped")
        val guarded = Runnable {
            if (!beginTask()) return@Runnable
            try {
                executor.execute {
                    try {
                        callback()
                    } finally {
                        completed()
                    }
                }
            } catch (_: RuntimeException) {
                completed() // Queue saturation is isolated to this binding.
            }
        }
        val future = if (repeat == null) {
            scheduler.schedule(guarded, first.toNanos(), TimeUnit.NANOSECONDS)
        } else {
            scheduler.scheduleWithFixedDelay(guarded, first.toNanos(), repeat.toNanos(), TimeUnit.NANOSECONDS)
        }
        scheduled.add(future)
        return object : PluginTask {
            override fun cancel(): Boolean = future.cancel(false)

            override val isCancelled: Boolean
                get() = future.isCancelled
        }
    }

    private fun completed() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized(idleMonitor) {
                idleMonitor.notifyAll()
            }
        }
    }

    private inner class Invocation(
        private val handler: PluginEventHandler,
        private val event: PluginEvent,
    ) : PluginExecution {
        private val result = CompletableFuture<Void>()
        private val cancellation = CancellationTokenSource()
        private val started = AtomicBoolean()
        private val finished = AtomicBoolean()
        private val callbackThread = AtomicReference<Thread>()
        private val resultMonitor = Object()

        @Volatile
        private var task: Runnable? = null

        fun submit() {
            val pending = Runnable(::run)
            task = pending
            executor.execute(pending)
        }

        private fun run() {
            if (!started.compareAndSet(false, true)) return
            if (cancellation.token.isCancellationRequested) {
                finish(CancellationException("Plugin invocation was cancelled before start"))
                return
            }
            callbackThread.set(Thread.currentThread())
            val scope = CancellationToken.activate(cancellation.token)
            try {
                val stage = requireNotNull(handler.handle(event)) { "plugin returned a null CompletionStage" }
                stage.whenComplete { _, failure -> finish(failure) }
            } catch (failure: Throwable) {
                finish(failure)
            } finally {
                scope.close()
                callbackThread.set(null)
            }
        }

        override fun stage(): CompletionStage<Void> = result.minimalCompletionStage()

        override fun cancel() {
            cancellation.cancel()
            callbackThread.get()?.interrupt()
            val pending = task
            if (!started.get() && pending != null && executor.remove(pending)) {
                finish(CancellationException("Plugin invocation was cancelled"))
            }
        }

        override fun isDone(): Boolean = result.isDone

        override fun await(timeout: Duration): Boolean {
            val deadline = System.nanoTime() + requireDelay(timeout, true).toNanos()
            synchronized(resultMonitor) {
                while (!result.isDone) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) return false
                    try {
                        TimeUnit.NANOSECONDS.timedWait(resultMonitor, remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return true
            }
        }

        fun finish(failure: Throwable?) {
            if (!finished.compareAndSet(false, true)) return
            if (failure == null) result.complete(null) else result.completeExceptionally(failure)
            completed()
            synchronized(resultMonitor) {
                resultMonitor.notifyAll()
            }
        }
    }

    @Synchronized
    private fun beginTask(): Boolean {
        if (closed) return false
        inFlight.incrementAndGet()
        return true
    }

    private inner class Registration(
        val id: String,
        private val eventTypes: Set<String>,
        val handler: PluginEventHandler,
    ) : EventSubscription {
        private var active = true

        fun matches(eventType: String): Boolean = active && (eventTypes.isEmpty() || eventTypes.contains(eventType))

        fun deactivate() {
            active = false
        }

        override val handlerId: String = id

        override val isActive: Boolean
            get() = active

        override fun close() {
            synchronized(this@BindingRuntimeResources) {
                deactivate()
                handlers.remove(id, this)
            }
        }
    }

    companion object {
        private fun requireDelay(value: Duration, allowZero: Boolean): Duration {
            if (value.isNegative || (!allowZero && value.isZero)) {
                throw IllegalArgumentException("duration is invalid")
            }
            return value
        }

        private fun validateHandlerId(value: String) {
            if (value.isBlank() || value.length > 128 ||
                value.codePoints().anyMatch(Character::isWhitespace)
            ) {
                throw IllegalArgumentException("handlerId is invalid")
            }
        }

        private fun daemonFactory(name: String): ThreadFactory = ThreadFactory { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

        private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
