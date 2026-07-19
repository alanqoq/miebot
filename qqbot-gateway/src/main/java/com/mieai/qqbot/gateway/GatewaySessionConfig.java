package com.mieai.qqbot.gateway;

import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.ShardSpec;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable connection and authentication settings for one Gateway session. */
public record GatewaySessionConfig(
        URI gatewayUrl,
        GatewayIntents intents,
        ShardSpec shardSpec,
        Duration heartbeatAckTimeout,
        Map<String, String> identifyProperties,
        Duration helloTimeout,
        Duration readyTimeout) {
    private static final Duration DEFAULT_ACK_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_HELLO_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Map<String, String> DEFAULT_PROPERTIES = Map.of(
            "$os", "linux",
            "$browser", "mieai-qqbot",
            "$device", "mieai-qqbot");

    public GatewaySessionConfig {
        validateGatewayUrl(gatewayUrl);
        Objects.requireNonNull(intents, "intents must not be null");
        Objects.requireNonNull(shardSpec, "shardSpec must not be null");
        requirePositive(heartbeatAckTimeout, "heartbeatAckTimeout");
        Objects.requireNonNull(identifyProperties, "identifyProperties must not be null");
        identifyProperties = Map.copyOf(identifyProperties);
        requirePositive(helloTimeout, "helloTimeout");
        requirePositive(readyTimeout, "readyTimeout");
    }

    /** Retains the original constructor while applying bounded protocol phase timeouts. */
    public GatewaySessionConfig(
            URI gatewayUrl,
            GatewayIntents intents,
            ShardSpec shardSpec,
            Duration heartbeatAckTimeout,
            Map<String, String> identifyProperties) {
        this(
                gatewayUrl,
                intents,
                shardSpec,
                heartbeatAckTimeout,
                identifyProperties,
                DEFAULT_HELLO_TIMEOUT,
                DEFAULT_READY_TIMEOUT);
    }

    public static GatewaySessionConfig defaults(
            URI gatewayUrl, GatewayIntents intents, ShardSpec shardSpec) {
        return new GatewaySessionConfig(
                gatewayUrl,
                intents,
                shardSpec,
                DEFAULT_ACK_TIMEOUT,
                DEFAULT_PROPERTIES,
                DEFAULT_HELLO_TIMEOUT,
                DEFAULT_READY_TIMEOUT);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void validateGatewayUrl(URI value) {
        Objects.requireNonNull(value, "gatewayUrl must not be null");
        String scheme = value.getScheme();
        if (!value.isAbsolute()
                || value.getHost() == null
                || scheme == null
                || !scheme.toLowerCase(Locale.ROOT).equals("wss")) {
            throw new IllegalArgumentException("gatewayUrl must be an absolute WSS URI with a host");
        }
    }
}
