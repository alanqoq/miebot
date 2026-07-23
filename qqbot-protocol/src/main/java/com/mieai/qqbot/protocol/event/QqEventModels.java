package com.mieai.qqbot.protocol.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Typed data objects for the current QQ Gateway event families. */
public final class QqEventModels {
    private QqEventModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventUser(
            String id,
            String username,
            String avatar,
            Boolean bot,
            @JsonProperty("user_openid") String userOpenId,
            @JsonProperty("member_openid") String memberOpenId,
            @JsonProperty("union_openid") String unionOpenId,
            @JsonProperty("union_user_account") String unionUserAccount) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Guild(
            String id,
            String name,
            String icon,
            @JsonProperty("owner_id") String ownerId,
            Boolean owner,
            @JsonProperty("member_count") Integer memberCount,
            @JsonProperty("max_members") Long maxMembers,
            String description,
            @JsonProperty("joined_at") String joinedAt,
            @JsonProperty("op_user_id") String operatorUserId) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Channel(
            String id,
            @JsonProperty("guild_id") String guildId,
            String name,
            Integer type,
            @JsonProperty("sub_type") Integer subType,
            Long position,
            @JsonProperty("parent_id") String parentId,
            @JsonProperty("owner_id") String ownerId,
            @JsonProperty("private_type") Integer privateType,
            @JsonProperty("speak_permission") Integer speakPermission,
            @JsonProperty("application_id") String applicationId,
            String permissions,
            @JsonProperty("op_user_id") String operatorUserId) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildMember(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("joined_at") String joinedAt,
            String nick,
            EventUser user,
            List<String> roles,
            @JsonProperty("op_user_id") String operatorUserId) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attachment(
            String id,
            String url,
            String filename,
            @JsonProperty("content_type") String contentType,
            Long size,
            Integer width,
            Integer height) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageReference(@JsonProperty("message_id") String messageId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String id,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("group_openid") String groupOpenId,
            String content,
            String timestamp,
            EventUser author,
            GuildMember member,
            List<EventUser> mentions,
            List<Attachment> attachments,
            @JsonProperty("message_reference") MessageReference messageReference,
            Long seq,
            @JsonProperty("seq_in_channel") String sequenceInChannel,
            @JsonProperty("src_guild_id") String sourceGuildId,
            @JsonProperty("direct_message") Boolean directMessage) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageDelete(
            Message message,
            @JsonProperty("op_user") EventUser operatorUser) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserLifecycle(
            @JsonProperty("openid") String openId,
            Long timestamp,
            Integer scene,
            @JsonProperty("scene_param") String sceneParameter) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupLifecycle(
            @JsonProperty("group_openid") String groupOpenId,
            @JsonProperty("op_member_openid") String operatorMemberOpenId,
            @JsonProperty("member_openid") String memberOpenId,
            Long timestamp) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Emoji(String id, Integer type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReactionTarget(String id, Integer type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageReaction(
            @JsonProperty("user_id") String userId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("guild_id") String guildId,
            ReactionTarget target,
            Emoji emoji) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageAudit(
            @JsonProperty("audit_id") String auditId,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("audit_time") String auditTime,
            @JsonProperty("create_time") String createTime,
            @JsonProperty("seq_in_channel") String sequenceInChannel) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumThreadInfo(
            @JsonProperty("thread_id") String threadId,
            String title,
            String content,
            @JsonProperty("date_time") String dateTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumThread(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("thread_info") ForumThreadInfo threadInfo) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumPostInfo(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("post_id") String postId,
            String content,
            @JsonProperty("date_time") String dateTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumPost(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("post_info") ForumPostInfo postInfo) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumReplyInfo(
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("post_id") String postId,
            @JsonProperty("reply_id") String replyId,
            String content,
            @JsonProperty("date_time") String dateTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumReply(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("reply_info") ForumReplyInfo replyInfo) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumAudit(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("author_id") String authorId,
            @JsonProperty("thread_id") String threadId,
            @JsonProperty("post_id") String postId,
            @JsonProperty("reply_id") String replyId,
            Integer type,
            Integer result,
            @JsonProperty("err_msg") String errorMessage,
            @JsonProperty("date_time") String dateTime) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioAction(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("audio_url") String audioUrl,
            String text) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AudioLiveMember(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("channel_type") Integer channelType,
            @JsonProperty("user_id") String userId) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InteractionResolved(
            @JsonProperty("button_data") String buttonData,
            @JsonProperty("button_id") String buttonId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("feature_id") String featureId,
            @JsonProperty("message_id") String messageId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InteractionData(Integer type, String name, InteractionResolved resolved) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Interaction(
            String id,
            @JsonProperty("application_id") String applicationId,
            Integer type,
            String scene,
            @JsonProperty("chat_type") Integer chatType,
            InteractionData data,
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("user_openid") String userOpenId,
            @JsonProperty("group_openid") String groupOpenId,
            @JsonProperty("group_member_openid") String groupMemberOpenId,
            String timestamp,
            Integer version) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubscribeMessageStatus(
            @JsonProperty("template_id") String templateId,
            @JsonProperty("openid") String openId,
            @JsonProperty("subscribe_status") Integer subscribeStatus) implements QqEventData {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EnterAio(
            @JsonProperty("user_openid") String userOpenId,
            @JsonProperty("from_source") String fromSource) implements QqEventData {}
}
