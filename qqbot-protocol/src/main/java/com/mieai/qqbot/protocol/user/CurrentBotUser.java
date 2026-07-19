package com.mieai.qqbot.protocol.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Bot user returned by {@code GET /users/@me}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CurrentBotUser(
        String id,
        String username,
        String avatar,
        boolean bot,
        @JsonProperty("union_openid") String unionOpenid,
        @JsonProperty("union_user_account") String unionUserAccount) {}
