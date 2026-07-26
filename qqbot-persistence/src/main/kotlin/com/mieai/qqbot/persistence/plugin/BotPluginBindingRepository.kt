package com.mieai.qqbot.persistence.plugin

import com.mieai.qqbot.domain.bot.BotId
import java.time.Instant
import java.util.UUID

interface BotPluginBindingRepository {
    fun findAll(): List<BotPluginBinding>

    fun findEnabled(): List<BotPluginBinding>

    fun findByBotId(botId: BotId): List<BotPluginBinding>

    fun findById(id: UUID): BotPluginBinding?

    fun findByPluginAndBot(pluginId: String, botId: BotId): BotPluginBinding?

    fun insert(binding: BotPluginBinding)

    fun update(binding: BotPluginBinding, expectedRevision: Long): BotPluginBinding

    fun touch(id: UUID, expectedRevision: Long, now: Instant): BotPluginBinding

    fun setRuntimeState(id: UUID, state: PluginBindingRuntimeState, error: String?, now: Instant)

    fun delete(id: UUID)
}
