package com.mieai.qqbot.persistence.lease;

import com.mieai.qqbot.domain.bot.BotId;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface BotLeaseRepository {
    Optional<BotLease> acquire(BotId botId, int shardIndex, String ownerId, Instant now, Duration duration);
    default Optional<BotLease> acquire(BotId botId, int shardIndex, String ownerId,
            Instant now, Duration duration, Map<String, String> pluginHashes) {
        return acquire(botId, shardIndex, ownerId, now, duration);
    }
    default boolean isOwned(BotId botId, int shardIndex, String ownerId, Instant now) {
        return false;
    }
    default boolean isOwned(BotId botId, String ownerId, Instant now) {
        return false;
    }
    default void registerPluginHashes(String ownerId, Map<String, String> pluginHashes,
            Instant now, Duration duration) {
        // Optional for legacy adapters.
    }
    default void unregisterPluginHashes(String ownerId) {
        // Optional for legacy adapters.
    }
    boolean renew(BotLease lease, Instant now, Duration duration);

    default boolean renew(BotLease lease, Instant now, Duration duration,
            Map<String, String> pluginHashes) {
        Objects.requireNonNull(pluginHashes, "pluginHashes must not be null");
        return renew(lease, now, duration);
    }
    boolean release(BotLease lease);
}
