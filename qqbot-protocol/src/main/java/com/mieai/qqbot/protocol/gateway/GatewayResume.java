package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Data sent with an opcode 6 Resume command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayResume(
        String token,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("seq") long sequence) {
    public GatewayResume {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
    }

    @Override
    public String toString() {
        return "GatewayResume[token=<redacted>, sessionId=" + sessionId + ", sequence=" + sequence + "]";
    }
}
