package com.mieai.qqbot.protocol.openapi

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Wire models for QQ guild API authorization and channel permission operations. */
object QqPermissionModels {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiPermission(
        val path: String?,
        val method: String?,
        val desc: String?,
        @JsonProperty("auth_status") val authorizationStatus: Int?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiPermissions(val apis: List<ApiPermission>?)

    data class ApiIdentifier(val path: String?, val method: String?)

    data class ApiPermissionDemandRequest(
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("api_identify") val apiIdentifier: ApiIdentifier?,
        val desc: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiPermissionDemand(
        @JsonProperty("guild_id") val guildId: String?,
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("api_identify") val apiIdentifier: ApiIdentifier?,
        val title: String?,
        val desc: String?,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChannelPermission(
        @JsonProperty("channel_id") val channelId: String?,
        @JsonProperty("user_id") val userId: String?,
        @JsonProperty("role_id") val roleId: String?,
        val permissions: String?,
    )

    data class ChannelPermissionUpdate(val add: String?, val remove: String?)
}
