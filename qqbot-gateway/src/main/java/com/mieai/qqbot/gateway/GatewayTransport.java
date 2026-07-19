package com.mieai.qqbot.gateway;

import java.net.URI;
import java.util.concurrent.CompletionStage;

/** Transport boundary for a text-based QQ Gateway WebSocket. */
@FunctionalInterface
public interface GatewayTransport {
    /**
     * Opens the exact URI returned by QQ. Implementations must not reconstruct or normalize it.
     * Listener callbacks must preserve wire order and start after the returned stage completes.
     */
    CompletionStage<GatewayConnection> connect(URI gatewayUrl, Listener listener);

    interface Listener {
        void onText(String payload);

        void onClosed(int statusCode, String reason);

        void onFailure(Throwable cause);
    }
}
