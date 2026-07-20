package com.mieai.qqbot.plugin.host;

import java.util.concurrent.atomic.AtomicBoolean;

/** Fences host capabilities after a binding is stopped, paused, or quarantined. */
final class BindingCapabilityGuard implements AutoCloseable {
    private final AtomicBoolean active = new AtomicBoolean(true);

    void requireActive() {
        if (!active.get()) throw new IllegalStateException("Plugin binding capabilities are fenced");
    }

    boolean isActive() {
        return active.get();
    }

    @Override public void close() {
        active.set(false);
    }
}
