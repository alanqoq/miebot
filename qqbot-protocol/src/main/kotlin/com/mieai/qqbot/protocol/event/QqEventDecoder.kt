package com.mieai.qqbot.protocol.event

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope
import com.mieai.qqbot.protocol.json.JsonCodec
import com.mieai.qqbot.protocol.json.JsonCodecs

/** Decodes known dispatch types while leaving future event names available as raw payloads. */
class QqEventDecoder(private val jsonCodec: JsonCodec) {

    fun supports(eventType: String?): Boolean = eventType != null && EVENT_TYPES.containsKey(eventType)

    fun decodeKnown(eventType: String, rawGatewayPayload: String): QqEventData? {
        val type = EVENT_TYPES[eventType] ?: return null
        @Suppress("UNCHECKED_CAST")
        val envelope = decodeEnvelope(rawGatewayPayload, type as Class<QqEventData>)
        require(envelope.eventType == null || eventType == envelope.eventType) {
            "eventType does not match the Gateway envelope"
        }
        return envelope.data
    }

    private fun <T : QqEventData> decodeEnvelope(rawGatewayPayload: String, type: Class<T>): GatewayEnvelope<T> =
        jsonCodec.decodeGatewayEnvelope(rawGatewayPayload, type)

    companion object {
        private val EVENT_TYPES: Map<String, Class<out QqEventData>> = mapOf(
            "GUILD_CREATE" to QqEventModels.Guild::class.java,
            "GUILD_UPDATE" to QqEventModels.Guild::class.java,
            "GUILD_DELETE" to QqEventModels.Guild::class.java,
            "CHANNEL_CREATE" to QqEventModels.Channel::class.java,
            "CHANNEL_UPDATE" to QqEventModels.Channel::class.java,
            "CHANNEL_DELETE" to QqEventModels.Channel::class.java,
            "GUILD_MEMBER_ADD" to QqEventModels.GuildMember::class.java,
            "GUILD_MEMBER_UPDATE" to QqEventModels.GuildMember::class.java,
            "GUILD_MEMBER_REMOVE" to QqEventModels.GuildMember::class.java,
            "MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "AT_MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "DIRECT_MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "GROUP_AT_MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "GROUP_MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "C2C_MESSAGE_CREATE" to QqEventModels.Message::class.java,
            "MESSAGE_DELETE" to QqEventModels.MessageDelete::class.java,
            "PUBLIC_MESSAGE_DELETE" to QqEventModels.MessageDelete::class.java,
            "DIRECT_MESSAGE_DELETE" to QqEventModels.MessageDelete::class.java,
            "MESSAGE_REACTION_ADD" to QqEventModels.MessageReaction::class.java,
            "MESSAGE_REACTION_REMOVE" to QqEventModels.MessageReaction::class.java,
            "MESSAGE_AUDIT_PASS" to QqEventModels.MessageAudit::class.java,
            "MESSAGE_AUDIT_REJECT" to QqEventModels.MessageAudit::class.java,
            "FORUM_THREAD_CREATE" to QqEventModels.ForumThread::class.java,
            "FORUM_THREAD_UPDATE" to QqEventModels.ForumThread::class.java,
            "FORUM_THREAD_DELETE" to QqEventModels.ForumThread::class.java,
            "FORUM_POST_CREATE" to QqEventModels.ForumPost::class.java,
            "FORUM_POST_DELETE" to QqEventModels.ForumPost::class.java,
            "FORUM_REPLY_CREATE" to QqEventModels.ForumReply::class.java,
            "FORUM_REPLY_DELETE" to QqEventModels.ForumReply::class.java,
            "FORUM_PUBLISH_AUDIT_RESULT" to QqEventModels.ForumAudit::class.java,
            "AUDIO_START" to QqEventModels.AudioAction::class.java,
            "AUDIO_FINISH" to QqEventModels.AudioAction::class.java,
            "AUDIO_ON_MIC" to QqEventModels.AudioAction::class.java,
            "AUDIO_OFF_MIC" to QqEventModels.AudioAction::class.java,
            "AUDIO_OR_LIVE_CHANNEL_MEMBER_ENTER" to QqEventModels.AudioLiveMember::class.java,
            "AUDIO_OR_LIVE_CHANNEL_MEMBER_EXIT" to QqEventModels.AudioLiveMember::class.java,
            "INTERACTION_CREATE" to QqEventModels.Interaction::class.java,
            "FRIEND_ADD" to QqEventModels.UserLifecycle::class.java,
            "FRIEND_DEL" to QqEventModels.UserLifecycle::class.java,
            "C2C_MSG_RECEIVE" to QqEventModels.UserLifecycle::class.java,
            "C2C_MSG_REJECT" to QqEventModels.UserLifecycle::class.java,
            "GROUP_ADD_ROBOT" to QqEventModels.GroupLifecycle::class.java,
            "GROUP_DEL_ROBOT" to QqEventModels.GroupLifecycle::class.java,
            "GROUP_MSG_RECEIVE" to QqEventModels.GroupLifecycle::class.java,
            "GROUP_MSG_REJECT" to QqEventModels.GroupLifecycle::class.java,
            "GROUP_MEMBER_ADD" to QqEventModels.GroupLifecycle::class.java,
            "GROUP_MEMBER_REMOVE" to QqEventModels.GroupLifecycle::class.java,
            "SUBSCRIBE_MESSAGE_STATUS" to QqEventModels.SubscribeMessageStatus::class.java,
            "ENTER_AIO" to QqEventModels.EnterAio::class.java,
        )

        private val DEFAULT = QqEventDecoder(JsonCodecs.defaultCodec())

        fun defaultDecoder(): QqEventDecoder = DEFAULT
    }
}
