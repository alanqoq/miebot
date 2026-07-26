package com.mieai.qqbot.protocol.openapi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.protocol.openapi.QqGuildModels.Member
import com.mieai.qqbot.protocol.openapi.QqGuildModels.User

/** Wire models for QQ reactions, announcements, pins, schedules, forums, and audio. */
object QqContentModels {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ReactionUsers(
        val users: List<User>?,
        val cookie: String?,
        @JsonProperty("is_end") val end: Boolean?,
    )

    data class RecommendChannel(
        @JsonProperty("channel_id") val channelId: String?,
        val introduce: String?,
    )

    data class GuildAnnouncementRequest(
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("message_id") val messageId: String?,
        @JsonProperty("announces_type") val announcementType: Int?,
        @JsonProperty("recommend_channels") val recommendChannels: List<RecommendChannel>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Announcement(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("message_id") val messageId: String?,
        @JsonProperty("announces_type") val announcementType: Int?,
        @JsonProperty("recommend_channels") val recommendChannels: List<RecommendChannel>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Pins(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("message_ids") val messageIds: List<String>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Schedule(
        val id: String?,
        val name: String?,
        val description: String?,
        @JsonProperty("start_timestamp") val startTimestamp: String?,
        @JsonProperty("end_timestamp") val endTimestamp: String?,
        val creator: Member?,
        @JsonProperty("jump_channel_id") val jumpChannelId: String?,
        @JsonProperty("remind_type") val remindType: String?,
    )

    data class ScheduleInput(
        val name: String?,
        val description: String?,
        @JsonProperty("start_timestamp") val startTimestamp: String?,
        @JsonProperty("end_timestamp") val endTimestamp: String?,
        @JsonProperty("jump_channel_id") val jumpChannelId: String?,
        @JsonProperty("remind_type") val remindType: String?,
    )

    data class ScheduleRequest(val schedule: ScheduleInput?)

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
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumThreads(
        val threads: List<ForumThread>?,
        @JsonProperty("is_finish") val finished: Int?,
    )

    data class ForumThreadRequest(val title: String?, val content: String?, val format: Int?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ForumThreadCreateResult(
        @JsonProperty("task_id") val taskId: String?,
        @JsonProperty("create_time") val createTime: String?,
    )

    data class AudioControl(
        @JsonProperty("audio_url") val audioUrl: String?,
        val text: String?,
        val status: Int?,
    )
}
