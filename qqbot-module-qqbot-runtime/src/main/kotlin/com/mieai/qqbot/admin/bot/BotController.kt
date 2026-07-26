package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.configuration.BotConfigurationView
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.configuration.CreateBotCommand
import com.mieai.qqbot.runtime.configuration.UpdateBotCommand
import com.mieai.qqbot.runtime.security.AppSecret
import jakarta.validation.Valid
import java.net.URI
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/bots")
class BotController(private val service: BotConfigurationService) {
    @GetMapping
    fun list(): List<BotConfigurationResponse> = service.findAll().map(BotConfigurationResponse::from)

    @GetMapping("/{botId}")
    fun get(@PathVariable botId: String): ResponseEntity<BotConfigurationResponse> {
        val id = BotId.parse(botId)
        val view = service.findById(id) ?: throw BotNotFoundException(id)
        return response(view)
    }

    @PostMapping
    fun create(@Valid @RequestBody request: CreateBotRequest): ResponseEntity<BotConfigurationResponse> {
        val view = AppSecret.of(requireNotNull(request.appSecret)).use { secret ->
            service.create(
                CreateBotCommand(
                    requireNotNull(request.displayName),
                    QqAppId.of(requireNotNull(request.appId)),
                    requireNotNull(request.environment),
                    GatewayIntents.of(request.intents),
                    ShardSpec(request.shardIndex, request.shardCount),
                    request.enabled,
                    secret,
                    request.maxMediaUploadBytes ?: BotDefinition.DEFAULT_MAX_MEDIA_UPLOAD_BYTES,
                ),
            )
        }
        return ResponseEntity.created(URI.create("/api/bots/${view.id}"))
            .eTag(etag(view))
            .body(BotConfigurationResponse.from(view))
    }

    @PutMapping("/{botId}")
    fun update(
        @PathVariable botId: String,
        @Valid @RequestBody request: UpdateBotRequest,
    ): ResponseEntity<BotConfigurationResponse> {
        val id = BotId.parse(botId)
        val secret = request.appSecret?.let(AppSecret::of)
        val view = try {
            service.update(
                UpdateBotCommand(
                    id,
                    BotRevision.of(request.expectedRevision),
                    requireNotNull(request.displayName),
                    QqAppId.of(requireNotNull(request.appId)),
                    requireNotNull(request.environment),
                    GatewayIntents.of(request.intents),
                    ShardSpec(request.shardIndex, request.shardCount),
                    secret,
                    request.maxMediaUploadBytes,
                ),
            )
        } finally {
            secret?.close()
        }
        return response(view)
    }

    @PatchMapping("/{botId}/enabled")
    fun setEnabled(
        @PathVariable botId: String,
        @Valid @RequestBody request: SetBotEnabledRequest,
    ): ResponseEntity<BotConfigurationResponse> {
        val id = BotId.parse(botId)
        val revision = BotRevision.of(request.expectedRevision)
        val view = if (request.enabled) service.enable(id, revision) else service.disable(id, revision)
        return response(view)
    }

    @DeleteMapping("/{botId}")
    fun delete(@PathVariable botId: String): ResponseEntity<Void> {
        service.delete(BotId.parse(botId))
        return ResponseEntity.noContent().build()
    }

    private fun response(view: BotConfigurationView): ResponseEntity<BotConfigurationResponse> =
        ResponseEntity.ok()
            .eTag(etag(view))
            .body(BotConfigurationResponse.from(view))

    private fun etag(view: BotConfigurationView): String = "\"${view.revision.value}\""
}
