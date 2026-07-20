package com.mieai.qqbot.persistence.plugin;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PluginDeliveryRepository {
    boolean createIfAbsent(UUID id, UUID eventId, UUID bindingId, String handlerId, Instant now);
    Optional<PluginDelivery> findById(UUID id);
    PluginDeliveryPage query(PluginDeliveryQuery query);
    PluginDeliveryQueueStats statistics();
    Optional<PluginDelivery> claimNext(String leaseOwner, Instant now, Duration leaseDuration);
    default Optional<PluginDelivery> claimNextOwned(
            String leaseOwner, String botLeaseOwner, Instant now, Duration leaseDuration) {
        return claimNext(leaseOwner, now, leaseDuration);
    }
    void markSucceeded(UUID id, long fencingToken, Instant now);
    void markRetry(UUID id, long fencingToken, Instant now, Instant availableAt, String error);
    void markDeadLetter(UUID id, long fencingToken, Instant now, String reason);
    int pauseForBinding(UUID bindingId, Instant now, String reason);
    int resumeForBinding(UUID bindingId, Instant now);
}
