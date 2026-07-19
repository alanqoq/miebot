package com.mieai.qqbot.persistence.lease;

import com.mieai.qqbot.domain.bot.BotId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface BotLeaseRepository {
    Optional<BotLease> acquire(BotId botId, int shardIndex, String ownerId, Instant now, Duration duration);
    boolean renew(BotLease lease, Instant now, Duration duration);
    boolean release(BotLease lease);
}
