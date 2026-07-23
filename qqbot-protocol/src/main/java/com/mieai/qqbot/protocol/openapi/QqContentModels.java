package com.mieai.qqbot.protocol.openapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mieai.qqbot.protocol.openapi.QqGuildModels.Member;
import com.mieai.qqbot.protocol.openapi.QqGuildModels.User;
import java.util.List;

/** Wire models for QQ reactions, announcements, pins, schedules, forums, and audio. */
public final class QqContentModels {
    private QqContentModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReactionUsers(
            List<User> users,
            String cookie,
            @JsonProperty("is_end") Boolean end) {}

    public record RecommendChannel(
            @JsonProperty("channel_id") String channelId,
            String introduce) {}

    public record GuildAnnouncementRequest(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("announces_type") Integer announcementType,
            @JsonProperty("recommend_channels") List<RecommendChannel> recommendChannels) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Announcement(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("announces_type") Integer announcementType,
            @JsonProperty("recommend_channels") List<RecommendChannel> recommendChannels) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pins(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("message_ids") List<String> messageIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(
            String id,
            String name,
            String description,
            @JsonProperty("start_timestamp") String startTimestamp,
            @JsonProperty("end_timestamp") String endTimestamp,
            Member creator,
            @JsonProperty("jump_channel_id") String jumpChannelId,
            @JsonProperty("remind_type") String remindType) {}

    public record ScheduleInput(
            String name,
            String description,
            @JsonProperty("start_timestamp") String startTimestamp,
            @JsonProperty("end_timestamp") String endTimestamp,
            @JsonProperty("jump_channel_id") String jumpChannelId,
            @JsonProperty("remind_type") String remindType) {}

    public record ScheduleRequest(ScheduleInput schedule) {}

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
            @JsonProperty("thread_info") ForumThreadInfo threadInfo) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumThreads(
            List<ForumThread> threads,
            @JsonProperty("is_finish") Integer finished) {}

    public record ForumThreadRequest(String title, String content, Integer format) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ForumThreadCreateResult(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("create_time") String createTime) {}

    public record AudioControl(
            @JsonProperty("audio_url") String audioUrl,
            String text,
            Integer status) {}
}
