package com.mieai.qqbot.protocol.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Bot user returned by {@code GET /users/@me}. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurrentBotUser(
    val id: String?,
    val username: String?,
    val avatar: String?,
    val bot: Boolean,
    @JsonProperty("union_openid") val unionOpenid: String?,
    @JsonProperty("union_user_account") val unionUserAccount: String?,
)
