package com.mieai.qqbot.persistence.bot

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision

/** Persistence contract for bot configuration and encrypted credentials. */
interface BotRepository {
    fun findById(id: BotId): StoredBot?

    fun findAll(): List<StoredBot>

    fun findEnabled(): List<StoredBot>

    fun insert(bot: StoredBot)

    /** Updates a bot if its stored revision matches [expectedRevision]. */
    fun update(bot: StoredBot, expectedRevision: BotRevision): BotRevision

    /** Deletes a bot and its durable runtime data as one database operation. */
    fun delete(id: BotId): Boolean
}
