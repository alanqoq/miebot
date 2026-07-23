package com.mieai.qqbot.protocol.openapi;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Wire models shared by QQ guild, channel, member, and role OpenAPI operations. */
public final class QqGuildModels {
    private QqGuildModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
            String id,
            String username,
            String avatar,
            Boolean bot,
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
            List<Channel> channels,
            @JsonProperty("union_world_id") String unionWorldId,
            @JsonProperty("union_org_id") String unionOrgId,
            @JsonProperty("op_user_id") String operatorUserId) {}

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
            @JsonProperty("op_user_id") String operatorUserId) {}

    public record ChannelUpdate(
            String name,
            Integer type,
            @JsonProperty("sub_type") Integer subType,
            Long position,
            @JsonProperty("parent_id") String parentId,
            @JsonProperty("private_type") Integer privateType,
            @JsonProperty("private_user_ids") List<String> privateUserIds,
            @JsonProperty("speak_permission") Integer speakPermission,
            @JsonProperty("application_id") String applicationId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("joined_at") String joinedAt,
            String nick,
            User user,
            List<String> roles,
            @JsonProperty("op_user_id") String operatorUserId) {}

    public record MemberDeleteRequest(
            @JsonProperty("add_blacklist") boolean addBlacklist,
            @JsonProperty("delete_history_msg_days") int deleteHistoryMessageDays) {}

    public record MemberRoleRequest(Channel channel) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Role(
            String id,
            String name,
            Long color,
            Integer hoist,
            Integer number,
            @JsonProperty("member_limit") @JsonAlias("number_limit") Integer memberLimit) {}

    public record RoleUpdate(String name, Long color, Integer hoist) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildRoles(
            @JsonProperty("guild_id") String guildId,
            List<Role> roles,
            @JsonProperty("role_num_limit") String roleNumberLimit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleUpdateResult(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("role_id") String roleId,
            Role role) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleMembersPage(List<Member> data, String next) {}

    public record MuteRequest(
            @JsonProperty("mute_end_timestamp") String muteEndTimestamp,
            @JsonProperty("mute_seconds") String muteSeconds,
            @JsonProperty("user_ids") List<String> userIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MuteResult(@JsonProperty("user_ids") List<String> userIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageSetting(
            @JsonProperty("disable_create_dm") Boolean disableCreateDirectMessage,
            @JsonProperty("disable_push_msg") Boolean disablePushMessage,
            @JsonProperty("channel_ids") List<String> channelIds,
            @JsonProperty("channel_push_max_num") Integer channelPushMaximum) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OnlineMemberCount(@JsonProperty("online_nums") Integer onlineMembers) {}
}
