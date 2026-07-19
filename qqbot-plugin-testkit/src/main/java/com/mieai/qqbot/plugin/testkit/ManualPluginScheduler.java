package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.PluginScheduler;
import com.mieai.qqbot.plugin.api.PluginTask;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Scheduler whose tasks run only when the test explicitly advances it. */
public final class ManualPluginScheduler implements PluginScheduler, AutoCloseable {
    private final List<ScheduledTask> tasks = new ArrayList<>();

    @Override
    public synchronized PluginTask schedule(Duration delay, Runnable task) {
        return add(delay, null, task);
    }

    @Override
    public synchronized PluginTask scheduleWithFixedDelay(Duration initialDelay, Duration delay, Runnable task) {
        requireDelay(delay, "delay", false);
        return add(initialDelay, delay, task);
    }

    public synchronized void runReady() {
        List<ScheduledTask> ready = tasks.stream().filter(value -> !value.cancelled && value.remaining.isZero()).toList();
        ready.forEach(ScheduledTask::run);
        tasks.removeIf(value -> value.cancelled);
    }

    public synchronized void advance(Duration elapsed) {
        requireDelay(elapsed, "elapsed", true);
        for (ScheduledTask task : tasks) {
            if (!task.cancelled) task.remaining = task.remaining.minus(elapsed).isNegative()
                    ? Duration.ZERO : task.remaining.minus(elapsed);
        }
        runReady();
    }

    @Override public synchronized void close() { tasks.forEach(ScheduledTask::cancel); tasks.clear(); }

    private ScheduledTask add(Duration initial, Duration repeat, Runnable callback) {
        requireDelay(initial, "initial delay", true);
        ScheduledTask task = new ScheduledTask(initial, repeat, Objects.requireNonNull(callback, "task must not be null"));
        tasks.add(task);
        return task;
    }

    private static void requireDelay(Duration value, String name, boolean allowZero) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative() || !allowZero && value.isZero()) throw new IllegalArgumentException(name + " is invalid");
    }

    private final class ScheduledTask implements PluginTask {
        private Duration remaining;
        private final Duration repeat;
        private final Runnable callback;
        private boolean cancelled;
        private ScheduledTask(Duration remaining, Duration repeat, Runnable callback) {
            this.remaining = remaining; this.repeat = repeat; this.callback = callback;
        }
        private void run() {
            callback.run();
            if (repeat == null) cancel(); else remaining = repeat;
        }
        @Override public boolean cancel() { boolean changed = !cancelled; cancelled = true; return changed; }
        @Override public boolean isCancelled() { return cancelled; }
    }
}
