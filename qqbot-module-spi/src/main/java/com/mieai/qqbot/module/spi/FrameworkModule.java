package com.mieai.qqbot.module.spi;

import com.mieai.qqbot.module.api.ModuleDescriptor;
import java.util.Objects;

/** Optional lifecycle entry point supplied by a startup-loaded framework module JAR. */
public interface FrameworkModule {
    ModuleDescriptor descriptor();

    default void start(ModuleContext context) {}

    default void stop() {}

    static FrameworkModule declarative(ModuleDescriptor descriptor) {
        ModuleDescriptor value = Objects.requireNonNull(descriptor, "descriptor must not be null");
        return () -> value;
    }
}
