package com.mieai.qqbot.client;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Gateway URL and shard/session recommendations returned by QQ. */
public record GatewayBotInfo(
        URI url, int recommendedShardCount, SessionStartLimit sessionStartLimit) {
    public GatewayBotInfo {
        Objects.requireNonNull(url, "url must not be null");
        String scheme = url.getScheme();
        if (!url.isAbsolute()
                || url.getHost() == null
                || scheme == null
                || !scheme.toLowerCase(Locale.ROOT).equals("wss")) {
            throw new IllegalArgumentException("url must be an absolute WSS URI with a host");
        }
        if (recommendedShardCount <= 0) {
            throw new IllegalArgumentException("recommendedShardCount must be positive");
        }
        Objects.requireNonNull(sessionStartLimit, "sessionStartLimit must not be null");
    }
}
