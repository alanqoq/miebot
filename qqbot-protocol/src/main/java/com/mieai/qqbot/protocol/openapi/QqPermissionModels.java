package com.mieai.qqbot.protocol.openapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Wire models for QQ guild API authorization and channel permission operations. */
public final class QqPermissionModels {
    private QqPermissionModels() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiPermission(
            String path,
            String method,
            String desc,
            @JsonProperty("auth_status") Integer authorizationStatus) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiPermissions(List<ApiPermission> apis) {}

    public record ApiIdentifier(String path, String method) {}

    public record ApiPermissionDemandRequest(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("api_identify") ApiIdentifier apiIdentifier,
            String desc) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiPermissionDemand(
            @JsonProperty("guild_id") String guildId,
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("api_identify") ApiIdentifier apiIdentifier,
            String title,
            String desc) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelPermission(
            @JsonProperty("channel_id") String channelId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("role_id") String roleId,
            String permissions) {}

    public record ChannelPermissionUpdate(String add, String remove) {}
}
