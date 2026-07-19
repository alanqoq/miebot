package com.mieai.qqbot.persistence.plugin;

import java.util.UUID;

public final class PluginBindingOptimisticLockException extends RuntimeException {
    private final UUID bindingId;
    private final long expectedRevision;

    public PluginBindingOptimisticLockException(UUID bindingId, long expectedRevision) {
        super("Plugin binding " + bindingId + " does not have expected revision " + expectedRevision);
        this.bindingId = bindingId;
        this.expectedRevision = expectedRevision;
    }

    public UUID bindingId() { return bindingId; }
    public long expectedRevision() { return expectedRevision; }
}
