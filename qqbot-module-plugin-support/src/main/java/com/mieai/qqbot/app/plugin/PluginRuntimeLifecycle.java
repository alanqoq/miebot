package com.mieai.qqbot.app.plugin;

import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

final class PluginRuntimeLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginRuntimeLifecycle.class);
    private final PluginRuntimeService runtime;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    PluginRuntimeLifecycle(PluginRuntimeService runtime, boolean enabled) {
        this.runtime = runtime;
        this.enabled = enabled;
    }

    @Override
    public void start() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            runtime.start();
        } catch (RuntimeException exception) {
            running.set(false);
            LOGGER.warn("Plugin runtime could not start ({})", exception.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) runtime.close();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override public boolean isRunning() { return running.get(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 1_200; }
}
