package com.mieai.qqbot.module.spi;

import com.mieai.qqbot.module.api.ModuleServiceKey;
import com.mieai.qqbot.module.api.ModuleServiceRegistration;
import java.util.Optional;

/** Lifecycle-scoped service exchange available to one framework module. */
public interface ModuleContext {
    String moduleId();
    <T> ModuleServiceRegistration publish(ModuleServiceKey<T> key, T service);
    <T> Optional<T> find(ModuleServiceKey<T> key);

    default <T> T require(ModuleServiceKey<T> key) {
        return find(key).orElseThrow(() -> new IllegalStateException(
                "Required module service is unavailable: " + key.name()));
    }
}
