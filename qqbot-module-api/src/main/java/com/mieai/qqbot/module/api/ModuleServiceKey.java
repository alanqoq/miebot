package com.mieai.qqbot.module.api;

import java.util.Objects;

/** Stable typed identifier used for communication between framework modules. */
public record ModuleServiceKey<T>(String name, Class<T> type) {
    public ModuleServiceKey {
        name = ModuleValidation.requireId(name, "name");
        Objects.requireNonNull(type, "type must not be null");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("service type must not be primitive");
        }
    }

    public static <T> ModuleServiceKey<T> of(String name, Class<T> type) {
        return new ModuleServiceKey<>(name, type);
    }
}
