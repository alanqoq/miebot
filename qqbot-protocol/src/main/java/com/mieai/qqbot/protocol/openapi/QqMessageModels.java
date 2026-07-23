package com.mieai.qqbot.protocol.openapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Additional wire models for direct messages, references, interactions, and bot links. */
public final class QqMessageModels {
    private QqMessageModels() {}

    public record MessageReference(
            @JsonProperty("message_id") String messageId,
            @JsonProperty("ignore_get_message_error") boolean ignoreGetMessageError) {}

    public record DirectMessageRequest(
            @JsonProperty("source_guild_id") String sourceGuildId,
            @JsonProperty("recipient_id") String recipientId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectMessage(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("create_time") String createTime) {}

    public record InteractionResponse(Integer code) {}

    public record UrlLinkRequest(@JsonProperty("callback_data") String callbackData) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UrlLink(String url) {}
}
