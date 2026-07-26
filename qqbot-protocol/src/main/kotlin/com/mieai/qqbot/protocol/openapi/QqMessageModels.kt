package com.mieai.qqbot.protocol.openapi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Additional wire models for direct messages, references, interactions, and bot links. */
object QqMessageModels {
    data class MessageReference(
        @JsonProperty("message_id") val messageId: String?,
        @JsonProperty("ignore_get_message_error") val ignoreGetMessageError: Boolean,
    )

    data class DirectMessageRequest(
        @JsonProperty("source_guild_id") val sourceGuildId: String?,
        @JsonProperty("recipient_id") val recipientId: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class DirectMessage(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("create_time") val createTime: String?,
    )

    data class InteractionResponse(val code: Int?)

    data class UrlLinkRequest(@JsonProperty("callback_data") val callbackData: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UrlLink(val url: String?)
}
