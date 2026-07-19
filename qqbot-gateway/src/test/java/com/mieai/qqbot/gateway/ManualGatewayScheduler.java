package com.mieai.qqbot.gateway;

import java.time.Duration;
import java.util.Comparator;
import java.util.PriorityQueue;

final class ManualGatewayScheduler implements GatewayScheduler {
    private final PriorityQueue<Task> tasks = new PriorityQueue<>(
            Comparator.comparingLong(Task::dueNanos).thenComparingLong(Task::order));
    private long nowNanos;
    private long nextOrder;
    private RuntimeException nextFailure;

    @Override
    public Cancellable schedule(Duration delay, Runnable task) {
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            throw failure;
        }
        Task scheduled = new Task(Math.addExact(nowNanos, delay.toNanos()), nextOrder++, task);
        tasks.add(scheduled);
        return () -> scheduled.cancelled = true;
    }

    void failNextSchedule() {
        nextFailure = new IllegalStateException("test scheduler rejection");
    }

    void advance(Duration duration) {
        long target = Math.addExact(nowNanos, duration.toNanos());
        while (!tasks.isEmpty() && tasks.peek().dueNanos <= target) {
            Task task = tasks.remove();
            nowNanos = task.dueNanos;
            if (!task.cancelled) {
                task.runnable.run();
            }
        }
        nowNanos = target;
    }

    private static final class Task {
        private final long dueNanos;
        private final long order;
        private final Runnable runnable;
        private boolean cancelled;

        private Task(long dueNanos, long order, Runnable runnable) {
            this.dueNanos = dueNanos;
            this.order = order;
            this.runnable = runnable;
        }

        private long dueNanos() {
            return dueNanos;
        }

        private long order() {
            return order;
        }
    }
}
