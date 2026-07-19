package com.mieai.qqbot.app.gateway;

import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/** Starts bot reconciliation after application infrastructure and stops it before dependencies. */
final class BotSupervisorLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotSupervisorLifecycle.class);
    private static final int PHASE = Integer.MAX_VALUE - 1_000;

    private final BotSupervisor supervisor;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    BotSupervisorLifecycle(BotSupervisor supervisor, boolean enabled) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            supervisor.start().whenComplete((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn(
                            "Initial bot runtime reconciliation failed ({})",
                            failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            supervisor.close();
        }
    }

    @Override
    public void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback must not be null");
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        try {
            supervisor.shutdown().whenComplete((ignored, failure) -> callback.run());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Bot runtime shutdown could not be started ({})",
                    exception.getClass().getSimpleName());
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
