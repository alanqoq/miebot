package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.ModuleServiceKey;
import com.mieai.qqbot.module.api.ModuleServiceRegistration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class DefaultModuleServiceRegistry {
    private final Map<ModuleServiceKey<?>, Entry<?>> entries = new HashMap<>();

    synchronized <T> ModuleServiceRegistration publish(
            String ownerModuleId, ModuleServiceKey<T> key, T service) {
        Objects.requireNonNull(ownerModuleId, "ownerModuleId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(service, "service must not be null");
        if (!key.type().isInstance(service)) {
            throw new IllegalArgumentException("service does not implement " + key.type().getName());
        }
        if (entries.containsKey(key)) {
            throw new IllegalStateException("Module service is already registered: " + key.name());
        }
        Registration registration = new Registration(ownerModuleId, key);
        entries.put(key, new Entry<>(ownerModuleId, service, registration));
        return registration;
    }

    synchronized <T> Optional<T> find(String requesterModuleId, ModuleServiceKey<T> key,
            java.util.Set<String> allowedOwners) {
        Objects.requireNonNull(requesterModuleId, "requesterModuleId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Entry<?> entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (!requesterModuleId.equals(entry.ownerModuleId())
                && !allowedOwners.contains(entry.ownerModuleId())) {
            throw new IllegalStateException("Module " + requesterModuleId
                    + " did not declare a dependency on service owner " + entry.ownerModuleId());
        }
        return Optional.of(key.type().cast(entry.service()));
    }

    synchronized void revokeOwner(String ownerModuleId) {
        entries.values().stream()
                .filter(entry -> entry.ownerModuleId().equals(ownerModuleId))
                .map(Entry::registration)
                .toList()
                .forEach(Registration::close);
    }

    private record Entry<T>(String ownerModuleId, T service, Registration registration) {}

    private final class Registration implements ModuleServiceRegistration {
        private final String ownerModuleId;
        private final ModuleServiceKey<?> key;
        private boolean active = true;

        private Registration(String ownerModuleId, ModuleServiceKey<?> key) {
            this.ownerModuleId = ownerModuleId;
            this.key = key;
        }

        @Override public String ownerModuleId() { return ownerModuleId; }
        @Override public ModuleServiceKey<?> key() { return key; }
        @Override public synchronized boolean isActive() { return active; }

        @Override
        public void close() {
            synchronized (DefaultModuleServiceRegistry.this) {
                if (!active) return;
                Entry<?> current = entries.get(key);
                if (current != null && current.registration() == this) {
                    entries.remove(key);
                }
                active = false;
            }
        }
    }
}
