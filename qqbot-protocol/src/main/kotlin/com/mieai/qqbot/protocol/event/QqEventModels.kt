package com.mieai.qqbot.protocol.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Typed data objects for the current QQ Gateway event families. */
object QqEventModels {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EventUser(
        val id: String?,
        val username: String?,
        val avatar: String?,
        val bot: Boolean?,
        @JsonProperty("user_openid") val userOpenId: String?,
        @JsonProperty("member_openid") val memberOpenId: String?,
        @JsonProperty("union_openid") val unionOpenId: String?,
        @JsonProperty("union_user_account") val unionUserAccount: String?,
        @JsonProperty("member_role") val memberRole: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Guild(
        val id: String?,
        val name: String?,
        val icon: String?,
        @JsonProperty("owner_id") val ownerId: String?,
        val owner: Boolean?,
        @JsonProperty("member_count") val memberCount: Int?,
        @JsonProperty("max_members") val maxMembers: Long?,
        val description: String?,
        @JsonProperty("joined_at") val joinedAt: String?,
        @JsonProperty("op_user_id") val operatorUserId: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Channel(
        val id: String?,
        @JsonProperty("guild_id") val guildId: String?,
        val name: String?,
        val type: Int?,
        @JsonProperty("sub_type") val subType: Int?,
        val position: Long?,
        @JsonProperty("parent_id") val parentId: String?,
        @JsonProperty("owner_id") val ownerId: String?,
        @JsonProperty("private_type") val privateType: Int?,
        @JsonProperty("speak_permission") val speakPermission: Int?,
        @JsonProperty("application_id") val applicationId: String?,
        val permissions: String?,
        @JsonProperty("op_user_id") val operatorUserId: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GuildMember(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("joined_at") val joinedAt: String?,
        val nick: String?,
        val user: EventUser?,
        val roles: List<String>?,
        @JsonProperty("op_user_id") val operatorUserId: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Attachment(
        val id: String?,
        val url: String?,
        val filename: String?,
        @JsonProperty("content_type") val contentType: String?,
        val size: Long?,
        val width: Int?,
        val height: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MessageReference(@JsonProperty("message_id") val messageId: String?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Message(
        val id: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("group_openid") val groupOpenId: String?,
        val content: String?,
        val timestamp: String?,
        val author: EventUser?,
        val member: GuildMember?,
        val mentions: List<EventUser>?,
        val attachments: List<Attachment>?,
        @JsonProperty("message_reference") val messageReference: MessageReference?,
        val seq: Long?,
        @JsonProperty("seq_in_channel") val sequenceInChannel: String?,
        @JsonProperty("src_guild_id") val sourceGuildId: String?,
        @JsonProperty("direct_message") val directMessage: Boolean?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MessageDelete(
        val message: Message?,
        @JsonProperty("op_user") val operatorUser: EventUser?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class UserLifecycle(
        @JsonProperty("openid") val openId: String?,
        val timestamp: Long?,
        val scene: Int?,
        @JsonProperty("scene_param") val sceneParameter: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GroupLifecycle(
        @JsonProperty("group_openid") val groupOpenId: String?,
        @JsonProperty("op_member_openid") val operatorMemberOpenId: String?,
        @JsonProperty("member_openid") val memberOpenId: String?,
        val timestamp: Long?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Emoji(val id: String?, val type: Int?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReactionTarget(val id: String?, val type: Int?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MessageReaction(
        @JsonProperty("user_id") val userId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("guild_id") val guildId: String?,
        val target: ReactionTarget?,
        val emoji: Emoji?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MessageAudit(
        @JsonProperty("audit_id") val auditId: String?,
        @JsonProperty("message_id") val messageId: String?,
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("audit_time") val auditTime: String?,
        @JsonProperty("create_time") val createTime: String?,
        @JsonProperty("seq_in_channel") val sequenceInChannel: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumThreadInfo(
        @JsonProperty("thread_id") val threadId: String?,
        val title: String?,
        val content: String?,
        @JsonProperty("date_time") val dateTime: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumThread(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("author_id") val authorId: String?,
        @JsonProperty("thread_info") val threadInfo: ForumThreadInfo?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumPostInfo(
        @JsonProperty("thread_id") val threadId: String?,
        @JsonProperty("post_id") val postId: String?,
        val content: String?,
        @JsonProperty("date_time") val dateTime: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumPost(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("author_id") val authorId: String?,
        @JsonProperty("post_info") val postInfo: ForumPostInfo?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumReplyInfo(
        @JsonProperty("thread_id") val threadId: String?,
        @JsonProperty("post_id") val postId: String?,
        @JsonProperty("reply_id") val replyId: String?,
        val content: String?,
        @JsonProperty("date_time") val dateTime: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumReply(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("author_id") val authorId: String?,
        @JsonProperty("reply_info") val replyInfo: ForumReplyInfo?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumAudit(
        @JsonProperty("task_id") val taskId: String?,
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("author_id") val authorId: String?,
        @JsonProperty("thread_id") val threadId: String?,
        @JsonProperty("post_id") val postId: String?,
        @JsonProperty("reply_id") val replyId: String?,
        val type: Int?,
        val result: Int?,
        @JsonProperty("err_msg") val errorMessage: String?,
        @JsonProperty("date_time") val dateTime: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AudioAction(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("audio_url") val audioUrl: String?,
        val text: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AudioLiveMember(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("channel_type") val channelType: Int?,
        @JsonProperty("user_id") val userId: String?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InteractionResolved(
        @JsonProperty("button_data") val buttonData: String?,
        @JsonProperty("button_id") val buttonId: String?,
        @JsonProperty("user_id") val userId: String?,
        @JsonProperty("feature_id") val featureId: String?,
        @JsonProperty("message_id") val messageId: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class InteractionData(val type: Int?, val name: String?, val resolved: InteractionResolved?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Interaction(
        val id: String?,
        @JsonProperty("application_id") val applicationId: String?,
        val type: Int?,
        val scene: String?,
        @JsonProperty("chat_type") val chatType: Int?,
        val data: InteractionData?,
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("user_openid") val userOpenId: String?,
        @JsonProperty("group_openid") val groupOpenId: String?,
        @JsonProperty("group_member_openid") val groupMemberOpenId: String?,
        val timestamp: String?,
        val version: Int?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SubscribeMessageStatus(
        @JsonProperty("template_id") val templateId: String?,
        @JsonProperty("openid") val openId: String?,
        @JsonProperty("subscribe_status") val subscribeStatus: Int?,
    ) : QqEventData

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class EnterAio(
        @JsonProperty("user_openid") val userOpenId: String?,
        @JsonProperty("from_source") val fromSource: String?,
    ) : QqEventData
}
