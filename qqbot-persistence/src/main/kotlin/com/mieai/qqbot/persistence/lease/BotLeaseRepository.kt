package com.mieai.qqbot.persistence.lease

import com.mieai.qqbot.domain.bot.BotId
import java.time.Duration
import java.time.Instant

interface BotLeaseRepository {
    fun acquire(botId: BotId, shardIndex: Int, ownerId: String, now: Instant, duration: Duration): BotLease?

    fun acquire(
        botId: BotId,
        shardIndex: Int,
        ownerId: String,
        now: Instant,
        duration: Duration,
        pluginHashes: Map<String, String>,
    ): BotLease?

    fun isOwned(botId: BotId, shardIndex: Int, ownerId: String, now: Instant): Boolean

    fun isOwned(botId: BotId, ownerId: String, now: Instant): Boolean

    fun registerPluginHashes(ownerId: String, pluginHashes: Map<String, String>, now: Instant, duration: Duration)

    fun unregisterPluginHashes(ownerId: String)

    fun renew(lease: BotLease, now: Instant, duration: Duration): Boolean

    fun renew(lease: BotLease, now: Instant, duration: Duration, pluginHashes: Map<String, String>): Boolean

    fun release(lease: BotLease): Boolean
}
