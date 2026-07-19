package com.mieai.qqbot.persistence.plugin;

import java.util.UUID;

public final class PluginDeliveryTransitionException extends RuntimeException {
    private final UUID deliveryId;
    private final long fencingToken;
    private final PluginDeliveryStatus targetStatus;

    public PluginDeliveryTransitionException(UUID deliveryId, long fencingToken, PluginDeliveryStatus targetStatus) {
        super("Plugin delivery " + deliveryId + " could not transition to " + targetStatus
                + " with fencing token " + fencingToken);
        this.deliveryId = deliveryId;
        this.fencingToken = fencingToken;
        this.targetStatus = targetStatus;
    }
}
