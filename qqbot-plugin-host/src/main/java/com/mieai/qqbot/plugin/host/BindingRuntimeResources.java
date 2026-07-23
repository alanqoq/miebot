package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.CancellationToken;
import com.mieai.qqbot.plugin.api.CancellationTokenSource;
import com.mieai.qqbot.plugin.api.EventService;
import com.mieai.qqbot.plugin.api.EventSubscription;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginEventHandler;
import com.mieai.qqbot.plugin.api.PluginScheduler;
import com.mieai.qqbot.plugin.api.PluginTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CancellationException;

/** All executable resources owned by one plugin binding. */
final class BindingRuntimeResources implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService scheduler;
    private final BindingCapabilityGuard capabilityGuard = new BindingCapabilityGuard();
    private final Map<String, Registration> handlers = new LinkedHashMap<>();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Object idleMonitor = new Object();
    private boolean closed;

    BindingRuntimeResources(String pluginId, String bindingId, int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("queueCapacity must be positive");
        String suffix = sanitize(pluginId) + "-" + sanitize(bindingId);
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), daemonFactory("qqbot-plugin-" + suffix),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                daemonFactory("qqbot-plugin-scheduler-" + suffix));
    }

    EventService eventService() {
        return this::subscribe;
    }

    BindingCapabilityGuard capabilityGuard() {
        return capabilityGuard;
    }

    PluginScheduler pluginScheduler() {
        return new PluginScheduler() {
            @Override public PluginTask schedule(Duration delay, Runnable task) {
                return scheduleTask(delay, null, task);
            }
            @Override public PluginTask scheduleWithFixedDelay(Duration initialDelay, Duration delay, Runnable task) {
                return scheduleTask(initialDelay, requireDelay(delay, false), task);
            }
        };
    }

    synchronized List<String> handlerIds() {
        return List.copyOf(handlers.keySet());
    }

    synchronized List<String> handlerIds(String eventType) {
        Objects.requireNonNull(eventType, "eventType must not be null");
        return handlers.values().stream()
                .filter(value -> value.matches(eventType))
                .map(value -> value.id)
                .toList();
    }

    CompletionStage<Void> execute(String handlerId, PluginEvent event) {
        return executeCancellable(handlerId, event).stage();
    }

    PluginExecution executeCancellable(String handlerId, PluginEvent event) {
        PluginEventHandler selected;
        synchronized (this) {
            if (closed) return completedExecution(new IllegalStateException("Plugin binding is stopped"));
            Registration registration = handlers.get(handlerId);
            if (registration == null) return completedExecution(null);
            if (registration != null && !registration.matches(event.eventType())) {
                return completedExecution(null);
            }
            selected = registration.handler;
        }
        inFlight.incrementAndGet();
        Invocation invocation = new Invocation(selected, event);
        try {
            invocation.submit();
        } catch (RuntimeException exception) {
            invocation.finish(new IllegalStateException("Plugin binding queue is full", exception));
        }
        return invocation;
    }

    private PluginExecution completedExecution(Throwable failure) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (failure == null) result.complete(null); else result.completeExceptionally(failure);
        return new PluginExecution() {
            @Override public CompletionStage<Void> stage() { return result.minimalCompletionStage(); }
            @Override public void cancel() { }
            @Override public boolean isDone() { return true; }
            @Override public boolean await(Duration timeout) { return true; }
        };
    }

    boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + requireDelay(timeout, true).toNanos();
        synchronized (idleMonitor) {
            while (inFlight.get() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) return false;
                try {
                    TimeUnit.NANOSECONDS.timedWait(idleMonitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @Override
    public void close() {
        beginShutdown();
        executor.shutdownNow();
    }

    /** Stop accepting new callbacks before the host waits for in-flight work. */
    void beginShutdown() {
        capabilityGuard.close();
        synchronized (this) {
            if (closed) return;
            closed = true;
            handlers.values().forEach(Registration::deactivate);
            handlers.clear();
            scheduled.forEach(value -> value.cancel(false));
            scheduled.clear();
        }
        scheduler.shutdownNow();
        // Let already accepted callbacks drain; the host applies its bounded
        // shutdown timeout and calls close() to interrupt anything remaining.
        executor.shutdown();
    }

    private synchronized EventSubscription subscribe(
            String handlerId, Set<String> eventTypes, PluginEventHandler handler) {
        validateHandlerId(handlerId);
        if (closed) throw new IllegalStateException("Plugin binding is stopped");
        Registration registration = new Registration(handlerId,
                Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes must not be null")),
                Objects.requireNonNull(handler, "handler must not be null"));
        if (handlers.putIfAbsent(handlerId, registration) != null) {
            throw new IllegalArgumentException("handlerId is already registered");
        }
        return registration;
    }

    private synchronized PluginTask scheduleTask(Duration initial, Duration repeat, Runnable callback) {
        Duration first = requireDelay(initial, true);
        Objects.requireNonNull(callback, "task must not be null");
        if (closed) throw new IllegalStateException("Plugin binding is stopped");
        Runnable guarded = () -> {
            if (!beginTask()) return;
            try {
                executor.execute(() -> {
                    try { callback.run(); }
                    finally { completed(); }
                });
            } catch (RuntimeException ignored) {
                completed(); // Queue saturation is isolated to this binding.
            }
        };
        ScheduledFuture<?> future = repeat == null
                ? scheduler.schedule(guarded, first.toNanos(), TimeUnit.NANOSECONDS)
                : scheduler.scheduleWithFixedDelay(guarded, first.toNanos(), repeat.toNanos(), TimeUnit.NANOSECONDS);
        scheduled.add(future);
        return new PluginTask() {
            @Override public boolean cancel() { return future.cancel(false); }
            @Override public boolean isCancelled() { return future.isCancelled(); }
        };
    }

    private void completed() {
        if (inFlight.decrementAndGet() == 0) {
            synchronized (idleMonitor) { idleMonitor.notifyAll(); }
        }
    }

    private final class Invocation implements PluginExecution {
        private final PluginEventHandler handler;
        private final PluginEvent event;
        private final CompletableFuture<Void> result = new CompletableFuture<>();
        private final CancellationTokenSource cancellation = new CancellationTokenSource();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicReference<Thread> callbackThread = new AtomicReference<>();
        private volatile Runnable task;

        private Invocation(PluginEventHandler handler, PluginEvent event) {
            this.handler = handler;
            this.event = event;
        }

        private void submit() {
            task = this::run;
            executor.execute(task);
        }

        private void run() {
            if (!started.compareAndSet(false, true)) return;
            if (cancellation.token().isCancellationRequested()) {
                finish(new CancellationException("Plugin invocation was cancelled before start"));
                return;
            }
            callbackThread.set(Thread.currentThread());
            try (CancellationToken.Scope ignored = CancellationToken.activate(cancellation.token())) {
                CompletionStage<Void> stage = Objects.requireNonNull(handler.handle(event),
                        "plugin returned a null CompletionStage");
                stage.whenComplete((value, failure) -> finish(failure));
            } catch (Throwable failure) {
                finish(failure);
            } finally {
                callbackThread.set(null);
            }
        }

        @Override public CompletionStage<Void> stage() { return result.minimalCompletionStage(); }

        @Override public void cancel() {
            cancellation.cancel();
            Thread thread = callbackThread.get();
            if (thread != null) thread.interrupt();
            if (!started.get() && task != null && executor.remove(task)) {
                finish(new CancellationException("Plugin invocation was cancelled"));
            }
        }

        @Override public boolean isDone() { return result.isDone(); }

        @Override public boolean await(Duration timeout) {
            long deadline = System.nanoTime() + requireDelay(timeout, true).toNanos();
            synchronized (result) {
                while (!result.isDone()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) return false;
                    try { TimeUnit.NANOSECONDS.timedWait(result, remaining); }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            }
        }

        private void finish(Throwable failure) {
            if (!finished.compareAndSet(false, true)) return;
            if (failure == null) result.complete(null); else result.completeExceptionally(failure);
            completed();
            synchronized (result) { result.notifyAll(); }
        }
    }

    private synchronized boolean beginTask() {
        if (closed) return false;
        inFlight.incrementAndGet();
        return true;
    }

    private static Duration requireDelay(Duration value, boolean allowZero) {
        Objects.requireNonNull(value, "duration must not be null");
        if (value.isNegative() || !allowZero && value.isZero()) throw new IllegalArgumentException("duration is invalid");
        return value;
    }

    private static void validateHandlerId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("handlerId is invalid");
        }
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; };
    }

    private static String sanitize(String value) { return value.replaceAll("[^A-Za-z0-9._-]", "_"); }

    private final class Registration implements EventSubscription {
        private final String id;
        private final Set<String> eventTypes;
        private final PluginEventHandler handler;
        private boolean active = true;
        private Registration(String id, Set<String> eventTypes, PluginEventHandler handler) {
            this.id = id; this.eventTypes = eventTypes; this.handler = handler;
        }
        private boolean matches(String eventType) { return active && (eventTypes.isEmpty() || eventTypes.contains(eventType)); }
        private void deactivate() { active = false; }
        @Override public String handlerId() { return id; }
        @Override public boolean isActive() { return active; }
        @Override public void close() { synchronized (BindingRuntimeResources.this) { deactivate(); handlers.remove(id, this); } }
    }
}
