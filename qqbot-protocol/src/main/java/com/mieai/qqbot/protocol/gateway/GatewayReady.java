package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mieai.qqbot.protocol.user.CurrentBotUser;
import java.util.Objects;

/** Data carried by the READY dispatch event. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayReady(
        int version,
        @JsonProperty("session_id") String sessionId,
        CurrentBotUser user,
        int[] shard) {
    public GatewayReady {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(shard, "shard must not be null");
        shard = shard.clone();
    }

    @Override
    public int[] shard() {
        return shard.clone();
    }
}
