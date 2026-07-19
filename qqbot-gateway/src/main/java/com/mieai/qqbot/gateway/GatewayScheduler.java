package com.mieai.qqbot.gateway;

import java.time.Duration;

/** Scheduling boundary used for heartbeats, ACK deadlines, and reconnect delays. */
@FunctionalInterface
public interface GatewayScheduler {
    Cancellable schedule(Duration delay, Runnable task);

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
