package com.mieai.qqbot.persistence.lease

import com.mieai.qqbot.domain.bot.BotId
import java.time.Instant

data class BotLease(
    val botId: BotId,
    val shardIndex: Int,
    val ownerId: String,
    val leaseUntil: Instant,
    val fencingToken: Long,
) {
    init {
        require(shardIndex >= 0) { "shardIndex must not be negative" }
        require(ownerId.isNotBlank() && ownerId.length <= 255 &&
            ownerId.codePoints().noneMatch { Character.isISOControl(it) }) {
            "ownerId is invalid"
        }
        require(fencingToken >= 1L) { "fencingToken must be positive" }
    }
}
