package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.domain.bot.BotEnvironment
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class UpdateBotRequest(
    @field:Positive val expectedRevision: Long,
    @field:NotBlank @field:Size(max = 128) val displayName: String?,
    @field:NotBlank @field:Size(max = 128) val appId: String?,
    @field:NotNull val environment: BotEnvironment?,
    @field:PositiveOrZero val intents: Long,
    @field:Min(0) @field:Max(4095) val shardIndex: Int,
    @field:Positive @field:Max(4096) val shardCount: Int,
    @field:Size(min = 1, max = 4096) val appSecret: String?,
    @field:Min(1_048_576) @field:Max(268_435_456) val maxMediaUploadBytes: Long? = null,
)
