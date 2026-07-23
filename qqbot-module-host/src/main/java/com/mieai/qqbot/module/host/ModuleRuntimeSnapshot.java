package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.ModuleDescriptor;
import java.util.Objects;
import java.util.Optional;

public record ModuleRuntimeSnapshot(
        ModuleDescriptor descriptor,
        ModuleRuntimeState state,
        Optional<String> error) {
    public ModuleRuntimeSnapshot {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(state, "state must not be null");
        error = Objects.requireNonNull(error, "error must not be null");
    }
}
