package com.mieai.qqbot.plugin.api;

/** A binding-scoped event subscription removed automatically when the plugin stops. */
public interface EventSubscription extends AutoCloseable {
    String handlerId();

    boolean isActive();

    @Override
    void close();
}
