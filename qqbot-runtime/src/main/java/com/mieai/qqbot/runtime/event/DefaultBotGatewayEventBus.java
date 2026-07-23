package com.mieai.qqbot.runtime.event;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ordered best-effort fan-out that never executes subscribers on a Gateway session thread. */
public final class DefaultBotGatewayEventBus
        implements BotGatewayEventSource, BotGatewayEventSink, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBotGatewayEventBus.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 2_048;

    private final CopyOnWriteArrayList<BotGatewayEventListener> listeners =
            new CopyOnWriteArrayList<>();
    private final ThreadPoolExecutor dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DefaultBotGatewayEventBus() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    DefaultBotGatewayEventBus(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        dispatcher = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "qqbot-gateway-event-source");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public AutoCloseable subscribe(BotGatewayEventListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        if (closed.get()) {
            throw new IllegalStateException("Gateway event source is closed");
        }
        listeners.add(listener);
        AtomicBoolean subscribed = new AtomicBoolean(true);
        return () -> {
            if (subscribed.compareAndSet(true, false)) {
                listeners.remove(listener);
            }
        };
    }

    @Override
    public void publish(BotGatewayEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (closed.get() || listeners.isEmpty()) {
            return;
        }
        try {
            dispatcher.execute(() -> deliver(event));
        } catch (RejectedExecutionException exception) {
            LOGGER.warn("Dropping live Gateway event {} for bot {} because subscriber queue is full",
                    event.dispatch().eventType(), event.botId());
        }
    }

    private void deliver(BotGatewayEvent event) {
        for (BotGatewayEventListener listener : listeners) {
            try {
                listener.onGatewayEvent(event);
            } catch (RuntimeException exception) {
                LOGGER.warn("Gateway event subscriber failed for bot {} ({})",
                        event.botId(), exception.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        dispatcher.shutdownNow();
    }
}
