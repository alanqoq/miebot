package com.mieai.qqbot.plugin.host

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.plugin.api.GroupMemberRole
import com.mieai.qqbot.plugin.api.InboundMessage
import com.mieai.qqbot.plugin.api.MessageTarget
import com.mieai.qqbot.plugin.api.MessageTargetType
import com.mieai.qqbot.plugin.api.PluginEvent
import java.io.IOException
import java.util.Locale

class PluginEventMapper(
    private val mapper: ObjectMapper,
) {
    fun map(event: InboxEvent): PluginEvent = PluginEvent(
        event.id,
        event.botId,
        event.environment,
        event.eventType,
        event.platformEventId,
        event.payload,
        event.receivedAt,
        message(event),
    )

    private fun message(event: InboxEvent): InboundMessage? {
        return try {
            val root = mapper.readTree(event.payload)
            val data = root.path("d")
            val type = event.eventType.uppercase(Locale.ROOT)
            val target = when {
                type.contains("C2C_MESSAGE") -> {
                    val id = first(data.path("author"), "user_openid", "member_openid", "id")
                        ?: return null
                    MessageTarget(MessageTargetType.C2C, id)
                }
                type.contains("GROUP") && type.contains("MESSAGE") -> {
                    val id = text(data, "group_openid") ?: return null
                    MessageTarget(MessageTargetType.GROUP, id)
                }
                type.contains("DIRECT_MESSAGE") -> {
                    val id = first(data, "guild_id", "src_guild_id") ?: return null
                    MessageTarget(MessageTargetType.DIRECT, id)
                }
                type.contains("MESSAGE") -> {
                    val id = text(data, "channel_id") ?: return null
                    MessageTarget(MessageTargetType.CHANNEL, id)
                }
                else -> return null
            }
            val messageId = first(data, "id", "msg_id", "message_id")
            val envelopeEventId = first(root, "id", "event_id")
            val authorId = first(data.path("author"), "user_openid", "member_openid", "id")
            val content = text(data, "content")
            val referencedMessageId = first(data.path("message_reference"), "message_id")
            val memberRole = if (target.type == MessageTargetType.GROUP) {
                GroupMemberRole.fromPlatformValue(text(data.path("author"), "member_role"))
            } else {
                null
            }
            InboundMessage(
                replyTarget = target,
                messageId = messageId,
                eventId = envelopeEventId ?: event.platformEventId,
                authorId = authorId,
                content = content,
                referencedMessageId = referencedMessageId,
                memberRole = memberRole,
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun first(node: JsonNode, vararg names: String): String? {
        for (name in names) {
            val value = text(node, name)
            if (value != null) return value
        }
        return null
    }

    private fun text(node: JsonNode, name: String): String? {
        val value = node.path(name)
        if (!value.isTextual) return null
        val text = value.textValue()
        return if (text == null || text.isBlank()) null else text
    }
}
