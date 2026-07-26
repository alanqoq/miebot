package com.mieai.qqbot.runtime.event
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class DefaultBotGatewayEventBus constructor(queueCapacity: Int = 2048) : BotGatewayEventSource, BotGatewayEventSink, AutoCloseable {
    private val logger = LoggerFactory.getLogger(DefaultBotGatewayEventBus::class.java)
    private val listeners = CopyOnWriteArrayList<BotGatewayEventListener>()
    private val dispatcher: ThreadPoolExecutor
    private val closed = AtomicBoolean()
    init {
        require(queueCapacity >= 1) { "queueCapacity must be positive" }
        dispatcher = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(queueCapacity), { runnable -> Thread(runnable, "qqbot-gateway-event-source").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    }
    override fun subscribe(listener: BotGatewayEventListener): AutoCloseable {
        check(!closed.get()) { "Gateway event source is closed" }; listeners.add(listener); val active = AtomicBoolean(true)
        return AutoCloseable { if (active.compareAndSet(true, false)) listeners.remove(listener) }
    }
    override fun publish(event: BotGatewayEvent) {
        if (closed.get() || listeners.isEmpty()) return
        try { dispatcher.execute { deliver(event) } } catch (_: RejectedExecutionException) { logger.warn("Dropping live Gateway event {} for bot {} because subscriber queue is full", event.dispatch.eventType, event.botId) }
    }
    private fun deliver(event: BotGatewayEvent) { listeners.forEach { listener -> try { listener.onGatewayEvent(event) } catch (e: RuntimeException) { logger.warn("Gateway event subscriber failed for bot {} ({})", event.botId, e::class.java.simpleName) } } }
    override fun close() { if (!closed.compareAndSet(false, true)) return; listeners.clear(); dispatcher.shutdownNow() }
}
