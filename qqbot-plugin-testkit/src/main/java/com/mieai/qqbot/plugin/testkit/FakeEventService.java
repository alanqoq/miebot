package com.mieai.qqbot.plugin.testkit;

import com.mieai.qqbot.plugin.api.EventService;
import com.mieai.qqbot.plugin.api.EventSubscription;
import com.mieai.qqbot.plugin.api.PluginEvent;
import com.mieai.qqbot.plugin.api.PluginEventHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Deterministic event registry for testing named plugin handlers. */
public final class FakeEventService implements EventService, AutoCloseable {
    private final Map<String, Registration> handlers = new LinkedHashMap<>();

    @Override
    public synchronized EventSubscription subscribe(
            String handlerId, Set<String> eventTypes, PluginEventHandler handler) {
        validateHandlerId(handlerId);
        Set<String> types = Set.copyOf(Objects.requireNonNull(eventTypes, "eventTypes must not be null"));
        Registration registration = new Registration(handlerId, types,
                Objects.requireNonNull(handler, "handler must not be null"));
        if (handlers.putIfAbsent(handlerId, registration) != null) {
            throw new IllegalArgumentException("handlerId is already registered");
        }
        return registration;
    }

    public synchronized Set<String> handlerIds() {
        return Set.copyOf(handlers.keySet());
    }

    public CompletionStage<Void> emit(PluginEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        java.util.List<Registration> selected;
        synchronized (this) {
            selected = handlers.values().stream().filter(value -> value.matches(event.eventType())).toList();
        }
        CompletionStage<Void> result = CompletableFuture.completedFuture(null);
        for (Registration registration : selected) {
            result = result.thenCompose(ignored -> registration.handler.handle(event));
        }
        return result;
    }

    @Override
    public synchronized void close() {
        // close() removes registrations from the map, so iterate over a snapshot.
        List<Registration> registrations = List.copyOf(handlers.values());
        handlers.clear();
        registrations.forEach(Registration::deactivate);
    }

    private static void validateHandlerId(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("handlerId is invalid");
        }
    }

    private final class Registration implements EventSubscription {
        private final String handlerId;
        private final Set<String> eventTypes;
        private final PluginEventHandler handler;
        private boolean active = true;

        private Registration(String handlerId, Set<String> eventTypes, PluginEventHandler handler) {
            this.handlerId = handlerId;
            this.eventTypes = eventTypes;
            this.handler = handler;
        }

        private boolean matches(String eventType) { return active && (eventTypes.isEmpty() || eventTypes.contains(eventType)); }
        private void deactivate() { active = false; }
        @Override public String handlerId() { return handlerId; }
        @Override public boolean isActive() { return active; }
        @Override public void close() { synchronized (FakeEventService.this) { deactivate(); handlers.remove(handlerId, this); } }
    }
}
