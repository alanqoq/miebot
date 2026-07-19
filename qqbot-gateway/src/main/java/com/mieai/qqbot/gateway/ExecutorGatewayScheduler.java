package com.mieai.qqbot.gateway;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Single daemon-thread scheduler suitable for one or more Gateway sessions. */
public final class ExecutorGatewayScheduler implements GatewayScheduler, AutoCloseable {
    private final ScheduledExecutorService executor;

    public ExecutorGatewayScheduler(String threadName) {
        Objects.requireNonNull(threadName, "threadName must not be null");
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "delay must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        var future = executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
