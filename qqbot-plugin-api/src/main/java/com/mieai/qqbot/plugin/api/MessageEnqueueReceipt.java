package com.mieai.qqbot.plugin.api;

import java.time.Instant;
import java.util.UUID;

public record MessageEnqueueReceipt(UUID jobId, boolean alreadyPresent, Instant queuedAt) {
    public MessageEnqueueReceipt {
        if (jobId == null || queuedAt == null) throw new NullPointerException("receipt fields must not be null");
    }
}
