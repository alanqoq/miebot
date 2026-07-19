package com.mieai.qqbot.plugin.api;

import java.time.Duration;

/** Binding-scoped scheduler; callbacks run through the binding's bounded executor. */
public interface PluginScheduler {
    PluginTask schedule(Duration delay, Runnable task);

    PluginTask scheduleWithFixedDelay(Duration initialDelay, Duration delay, Runnable task);

    static PluginScheduler denied() {
        return new PluginScheduler() {
            @Override
            public PluginTask schedule(Duration delay, Runnable task) {
                throw new SecurityException("Plugin scheduler capability is not granted");
            }

            @Override
            public PluginTask scheduleWithFixedDelay(Duration initialDelay, Duration delay, Runnable task) {
                throw new SecurityException("Plugin scheduler capability is not granted");
            }
        };
    }
}
