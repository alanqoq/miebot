package com.mieai.qqbot.persistence.bot

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision

/** Raised when a bot configuration was changed or removed after it was read. */
class OptimisticLockException(
    val botId: BotId,
    val expectedRevision: BotRevision,
) : RuntimeException(
    "Bot $botId does not have expected revision ${expectedRevision.value}",
)
