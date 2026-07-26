package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository
import com.mieai.qqbot.onebot11.mapping.OneBotStoredMessage
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.protocol.event.QqEventModels
import com.mieai.qqbot.runtime.event.BotGatewayEvent
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

/** Converts only QQ C2C and ordinary-group events into standard OneBot 11 events. */
class OneBotEventMapper(
    private val objectMapper: ObjectMapper,
    private val bots: BotRepository,
    private val entityIds: OneBotEntityIdRepository,
    private val messages: OneBotMessageRepository,
    private val messageCodec: OneBotMessageCodec,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun map(event: BotGatewayEvent): ObjectNode? = when (event.dispatch.eventType) {
        "C2C_MESSAGE_CREATE" -> mapMessage(event, false)
        "GROUP_AT_MESSAGE_CREATE", "GROUP_MESSAGE_CREATE" -> mapMessage(event, true)
        "FRIEND_ADD" -> mapFriendAdd(event)
        "GROUP_ADD_ROBOT", "GROUP_DEL_ROBOT", "GROUP_MEMBER_ADD", "GROUP_MEMBER_REMOVE" ->
            mapGroupLifecycle(event)
        else -> null
    }

    fun selfId(botId: BotId): Long {
        val appId = (bots.findById(botId)
            ?: throw IllegalArgumentException("Bot does not exist"))
            .definition
            .appId
            .value
        return entityIds.aliasFor(botId, OneBotEntityType.SELF, "", appId)
    }

    private fun mapMessage(event: BotGatewayEvent, group: Boolean): ObjectNode? {
        val message = event.dispatch.decodeKnownEvent() as? QqEventModels.Message ?: return null
        val groupRawId = if (group) normalized(message.groupOpenId) else null
        val userRawId = authorId(message.author, group)
        val officialMessageId = normalized(message.id)
        if (userRawId == null || officialMessageId == null || (group && groupRawId == null)) {
            return null
        }

        val botId = event.botId
        val selfId = selfId(botId)
        val userId = entityIds.aliasFor(
            botId,
            OneBotEntityType.USER,
            if (group) groupRawId!! else "",
            userRawId,
        )
        val groupId = if (group) {
            entityIds.aliasFor(botId, OneBotEntityType.GROUP, "", groupRawId!!)
        } else {
            0L
        }
        val segments = segments(botId, message)
        val eventTime = parseTime(message.timestamp, event.receivedAt)
        val sender = sender(message, userId, group)
        val messageId = messages.record(
            botId,
            officialMessageId,
            if (group) QqMessageTargetType.GROUP else QqMessageTargetType.C2C,
            if (group) groupRawId!! else userRawId,
            OneBotStoredMessage.Direction.INCOMING,
            if (group) "group" else "private",
            eventTime,
            userId,
            encode(messageCodec.toArray(segments)),
            encode(sender),
        )

        val result = objectMapper.createObjectNode()
        result.put("time", eventTime)
        result.put("self_id", selfId)
        result.put("post_type", "message")
        result.put("message_type", if (group) "group" else "private")
        result.put("sub_type", if (group) "normal" else "friend")
        result.put("message_id", messageId)
        result.put("user_id", userId)
        if (group) {
            result.put("group_id", groupId)
        }
        result.set<ArrayNode>("message", messageCodec.toArray(segments))
        result.put("raw_message", messageCodec.toRawMessage(segments))
        result.put("font", 0)
        result.set<ObjectNode>("sender", sender)
        return result
    }

    private fun mapFriendAdd(event: BotGatewayEvent): ObjectNode? {
        val lifecycle = event.dispatch.decodeKnownEvent() as? QqEventModels.UserLifecycle ?: return null
        val openId = normalized(lifecycle.openId) ?: return null
        val userId = entityIds.aliasFor(event.botId, OneBotEntityType.USER, "", openId)
        val result = baseNotice(event.botId, epochSeconds(lifecycle.timestamp, event.receivedAt))
        result.put("notice_type", "friend_add")
        result.put("user_id", userId)
        return result
    }

    private fun mapGroupLifecycle(event: BotGatewayEvent): ObjectNode? {
        val lifecycle = event.dispatch.decodeKnownEvent() as? QqEventModels.GroupLifecycle ?: return null
        val groupRawId = normalized(lifecycle.groupOpenId) ?: return null
        val type = event.dispatch.eventType
        val increase = type.endsWith("ADD_ROBOT") || type.endsWith("MEMBER_ADD")
        val robot = type.endsWith("ROBOT")
        val memberRawId = normalized(lifecycle.memberOpenId)
        if (!robot && memberRawId == null) {
            return null
        }
        val selfId = selfId(event.botId)
        val groupId = entityIds.aliasFor(event.botId, OneBotEntityType.GROUP, "", groupRawId)
        val userId = if (robot) {
            selfId
        } else {
            entityIds.aliasFor(event.botId, OneBotEntityType.USER, groupRawId, memberRawId!!)
        }
        val operatorRawId = normalized(lifecycle.operatorMemberOpenId)
        val operatorId = if (operatorRawId == null) {
            userId
        } else {
            entityIds.aliasFor(event.botId, OneBotEntityType.USER, groupRawId, operatorRawId)
        }

        val result = baseNotice(event.botId, epochSeconds(lifecycle.timestamp, event.receivedAt))
        result.put("notice_type", if (increase) "group_increase" else "group_decrease")
        result.put("sub_type", lifecycleSubtype(increase, robot, memberRawId, operatorRawId))
        result.put("group_id", groupId)
        result.put("operator_id", operatorId)
        result.put("user_id", userId)
        return result
    }

    private fun baseNotice(botId: BotId, eventTime: Long): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("time", eventTime)
            put("self_id", selfId(botId))
            put("post_type", "notice")
        }

    private fun segments(botId: BotId, message: QqEventModels.Message): List<OneBotSegment> {
        val result = mutableListOf<OneBotSegment>()
        val referenceId = message.messageReference?.messageId
        if (normalized(referenceId) != null) {
            messages.findByOfficial(botId, referenceId!!)?.let { reference ->
                result += OneBotSegment(
                    "reply",
                    mapOf("id" to reference.messageId.toString()),
                )
            }
        }
        val content = message.content
        if (!content.isNullOrEmpty()) {
            result += OneBotMessageCodec.text(content)
        }
        message.attachments?.forEach { attachment ->
            val url = normalized(attachment.url)
            val type = attachmentType(attachment.contentType)
            if (url != null && type != null) {
                val data = if (type == "image") {
                    mapOf("file" to url, "url" to url)
                } else {
                    mapOf("file" to url)
                }
                result += OneBotSegment(type, data)
            }
        }
        if (result.isEmpty()) {
            result += OneBotMessageCodec.text("")
        }
        return result
    }

    private fun sender(message: QqEventModels.Message, userId: Long, group: Boolean): ObjectNode {
        val sender = objectMapper.createObjectNode()
        sender.put("user_id", userId)
        val nickname = when {
            normalized(message.member?.nick) != null -> message.member!!.nick!!
            normalized(message.author?.username) != null -> message.author!!.username!!
            else -> ""
        }
        sender.put("nickname", nickname)
        sender.put("sex", "unknown")
        sender.put("age", 0)
        if (group) {
            sender.put("card", nickname)
            sender.put("area", "")
            sender.put("level", "")
            sender.put("role", "member")
            sender.put("title", "")
        }
        return sender
    }

    private fun encode(value: Any): String = try {
        objectMapper.writeValueAsString(value)
    } catch (exception: JsonProcessingException) {
        throw IllegalStateException("Unable to encode OneBot message", exception)
    }

    companion object {
        private fun authorId(author: QqEventModels.EventUser?, group: Boolean): String? {
            if (author == null) return null
            val primary = if (group) normalized(author.memberOpenId) else normalized(author.userOpenId)
            if (primary != null) return primary
            val secondary = if (group) normalized(author.userOpenId) else normalized(author.memberOpenId)
            return secondary ?: normalized(author.id)
        }

        private fun attachmentType(contentType: String?): String? {
            val value = contentType?.lowercase(Locale.ROOT) ?: return null
            return when {
                value.startsWith("image/") -> "image"
                value.startsWith("audio/") -> "record"
                value.startsWith("video/") -> "video"
                else -> null
            }
        }

        private fun lifecycleSubtype(
            increase: Boolean,
            robot: Boolean,
            memberId: String?,
            operatorId: String?,
        ): String {
            if (increase) {
                return if (operatorId != null && operatorId != memberId) "invite" else "approve"
            }
            if (robot) return "kick_me"
            return if (operatorId == null || operatorId == memberId) "leave" else "kick"
        }

        private fun parseTime(value: String?, fallback: Instant): Long {
            if (value != null) {
                try {
                    return Instant.parse(value).epochSecond
                } catch (_: DateTimeParseException) {
                    // Use durable receipt time for malformed or future timestamp formats.
                }
            }
            return fallback.epochSecond
        }

        private fun epochSeconds(value: Long?, fallback: Instant): Long {
            if (value == null || value < 0L) return fallback.epochSecond
            return if (value > 10_000_000_000L) value / 1_000L else value
        }

        private fun normalized(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
    }
}
