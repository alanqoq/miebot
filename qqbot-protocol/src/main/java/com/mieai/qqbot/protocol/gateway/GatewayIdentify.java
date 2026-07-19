package com.mieai.qqbot.protocol.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;
import java.util.Objects;

/** Data sent with an opcode 2 Identify command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayIdentify(
        String token, long intents, int[] shard, Map<String, String> properties) {
    public GatewayIdentify {
        Objects.requireNonNull(token, "token must not be null");
        Objects.requireNonNull(shard, "shard must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        shard = shard.clone();
        properties = Map.copyOf(properties);
    }

    @Override
    public int[] shard() {
        return shard.clone();
    }

    @Override
    public String toString() {
        return "GatewayIdentify[token=<redacted>, intents=" + intents + ", shard=<redacted>]";
    }
}
