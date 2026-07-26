package com.mieai.qqbot.protocol.openapi

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Wire models shared by QQ guild, channel, member, and role OpenAPI operations. */
object QqGuildModels {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class User(
        val id: String?,
        val username: String?,
        val avatar: String?,
        val bot: Boolean?,
        @JsonProperty("union_openid") val unionOpenId: String?,
        @JsonProperty("union_user_account") val unionUserAccount: String?,
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
        val channels: List<Channel>?,
        @JsonProperty("union_world_id") val unionWorldId: String?,
        @JsonProperty("union_org_id") val unionOrgId: String?,
        @JsonProperty("op_user_id") val operatorUserId: String?,
    )

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
    )

    data class ChannelUpdate(
        val name: String?,
        val type: Int?,
        @JsonProperty("sub_type") val subType: Int?,
        val position: Long?,
        @JsonProperty("parent_id") val parentId: String?,
        @JsonProperty("private_type") val privateType: Int?,
        @JsonProperty("private_user_ids") val privateUserIds: List<String>?,
        @JsonProperty("speak_permission") val speakPermission: Int?,
        @JsonProperty("application_id") val applicationId: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Member(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("joined_at") val joinedAt: String?,
        val nick: String?,
        val user: User?,
        val roles: List<String>?,
        @JsonProperty("op_user_id") val operatorUserId: String?,
    )

    data class MemberDeleteRequest(
        @JsonProperty("add_blacklist") val addBlacklist: Boolean,
        @JsonProperty("delete_history_msg_days") val deleteHistoryMessageDays: Int,
    )

    data class MemberRoleRequest(val channel: Channel?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Role(
        val id: String?,
        val name: String?,
        val color: Long?,
        val hoist: Int?,
        val number: Int?,
        @JsonProperty("member_limit") @JsonAlias("number_limit") val memberLimit: Int?,
    )

    data class RoleUpdate(val name: String?, val color: Long?, val hoist: Int?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class GuildRoles(
        @JsonProperty("guild_id") val guildId: String?,
        val roles: List<Role>?,
        @JsonProperty("role_num_limit") val roleNumberLimit: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RoleUpdateResult(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("role_id") val roleId: String?,
        val role: Role?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RoleMembersPage(val data: List<Member>?, val next: String?)

    data class MuteRequest(
        @JsonProperty("mute_end_timestamp") val muteEndTimestamp: String?,
        @JsonProperty("mute_seconds") val muteSeconds: String?,
        @JsonProperty("user_ids") val userIds: List<String>?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MuteResult(@JsonProperty("user_ids") val userIds: List<String>?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MessageSetting(
        @JsonProperty("disable_create_dm") val disableCreateDirectMessage: Boolean?,
        @JsonProperty("disable_push_msg") val disablePushMessage: Boolean?,
        @JsonProperty("channel_ids") val channelIds: List<String>?,
        @JsonProperty("channel_push_max_num") val channelPushMaximum: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OnlineMemberCount(@JsonProperty("online_nums") val onlineMembers: Int?)
}
