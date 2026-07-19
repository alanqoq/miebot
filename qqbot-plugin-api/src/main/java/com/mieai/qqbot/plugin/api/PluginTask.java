package com.mieai.qqbot.plugin.api;

/** A host-owned scheduled task that is cancelled when its binding stops. */
public interface PluginTask extends AutoCloseable {
    boolean cancel();

    boolean isCancelled();

    @Override
    default void close() {
        cancel();
    }
}
