package com.mieai.qqbot.app.outbox;

import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

final class OutboxRuntimeLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRuntimeLifecycle.class);
    private final ProductionOutboxWorker worker;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    OutboxRuntimeLifecycle(ProductionOutboxWorker worker, boolean enabled) {
        this.worker = worker;
        this.enabled = enabled;
    }

    @Override public void start() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try { worker.start(); }
        catch (RuntimeException exception) {
            running.set(false);
            LOGGER.warn("Outbox worker could not start ({})", exception.getClass().getSimpleName());
        }
    }
    @Override public void stop() { if (running.compareAndSet(true, false)) worker.close(); }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 600; }
}
