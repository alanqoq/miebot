package com.mieai.qqbot.plugin.api;

import java.util.Set;

/** Registers named handlers. An empty event-type set subscribes to every event. */
public interface EventService {
    EventSubscription subscribe(String handlerId, Set<String> eventTypes, PluginEventHandler handler);

    static EventService denied() {
        return (handlerId, eventTypes, handler) -> {
            throw new SecurityException("Plugin event subscription capability is not granted");
        };
    }
}
