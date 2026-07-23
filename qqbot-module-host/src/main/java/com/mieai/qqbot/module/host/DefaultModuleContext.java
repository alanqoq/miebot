package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.ModuleDescriptor;
import com.mieai.qqbot.module.api.ModuleServiceKey;
import com.mieai.qqbot.module.api.ModuleServiceRegistration;
import com.mieai.qqbot.module.spi.ModuleContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class DefaultModuleContext implements ModuleContext, AutoCloseable {
    private final ModuleDescriptor descriptor;
    private final DefaultModuleServiceRegistry registry;
    private final Set<String> allowedOwners;
    private boolean active = true;

    DefaultModuleContext(ModuleDescriptor descriptor, DefaultModuleServiceRegistry registry) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        allowedOwners = descriptor.dependencies().stream()
                .map(com.mieai.qqbot.module.api.ModuleDependency::moduleId)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override public String moduleId() { return descriptor.id(); }

    @Override
    public synchronized <T> ModuleServiceRegistration publish(ModuleServiceKey<T> key, T service) {
        requireActive();
        return registry.publish(descriptor.id(), key, service);
    }

    @Override
    public synchronized <T> Optional<T> find(ModuleServiceKey<T> key) {
        requireActive();
        return registry.find(descriptor.id(), key, allowedOwners);
    }

    @Override
    public synchronized void close() {
        if (!active) return;
        registry.revokeOwner(descriptor.id());
        active = false;
    }

    private void requireActive() {
        if (!active) throw new IllegalStateException("Module context is closed: " + descriptor.id());
    }
}
