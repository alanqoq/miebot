package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.client.FileMediaAssetStore
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import jakarta.validation.Valid
import java.io.IOException
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/bots")
class MediaController(
    private val bots: BotRepository,
    private val mediaStore: MediaAssetStore,
    private val messages: BotMessageAdministrationService,
) {
    @PostMapping(path = ["/{botId}/media"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @PathVariable botId: String,
        @RequestParam kind: QqMediaKind,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<MediaUploadResponse> {
        val id = BotId.parse(botId)
        val bot = bots.findById(id) ?: throw BotMessageAdministrationException(
            HttpStatus.NOT_FOUND,
            "BOT_NOT_FOUND",
            "Bot does not exist",
        )
        val max = bot.definition.maxMediaUploadBytes
        if (file.size > max) throw MediaUploadTooLargeException(file.size, max)
        try {
            val asset = mediaStore.stage(
                id,
                kind,
                file.originalFilename ?: "upload.bin",
                file.contentType ?: "application/octet-stream",
                file.inputStream,
                max,
            )
            return ResponseEntity.status(HttpStatus.CREATED).body(MediaUploadResponse.from(asset))
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to read uploaded media", exception)
        } catch (exception: FileMediaAssetStore.MediaSizeExceededException) {
            throw MediaUploadTooLargeException(file.size, exception.maxBytes())
        }
    }

    @PostMapping(path = ["/{botId}/messages"], consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun send(
        @PathVariable botId: String,
        @Valid @RequestBody request: SendBotMessageRequest,
    ): ResponseEntity<BotMessageResponse> =
        ResponseEntity.accepted().body(messages.send(botId, request))
}
