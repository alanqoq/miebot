package com.mieai.qqbot.onebot11

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.config.OneBot11ConfigurationService
import com.mieai.qqbot.onebot11.config.OneBot11SettingsRequest
import com.mieai.qqbot.onebot11.config.OneBot11SettingsView
import com.mieai.qqbot.onebot11.transport.OneBotRuntimeManager
import com.mieai.qqbot.onebot11.transport.OneBotTransportStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/bots/{botId}/onebot11")
class OneBot11SettingsController(
    private val configurations: OneBot11ConfigurationService,
    private val runtimes: OneBotRuntimeManager,
) {
    @GetMapping
    fun get(@PathVariable botId: String): OneBot11SettingsResponse {
        val id = BotId.parse(botId)
        return response(configurations.get(id), runtimes.status(id))
    }

    @PutMapping
    fun update(
        @PathVariable botId: String,
        @RequestBody request: OneBot11SettingsRequest,
    ): OneBot11SettingsResponse {
        val id = BotId.parse(botId)
        val saved = configurations.update(id, request)
        runtimes.reconcile(id)
        return response(saved, runtimes.status(id))
    }

    private fun response(
        settings: OneBot11SettingsView,
        runtime: OneBotTransportStatus,
    ): OneBot11SettingsResponse = OneBot11SettingsResponse(settings, runtime)

    data class OneBot11SettingsResponse(
        val settings: OneBot11SettingsView,
        val runtime: OneBotTransportStatus,
    )
}
