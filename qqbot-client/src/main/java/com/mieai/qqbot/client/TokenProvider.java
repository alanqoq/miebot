package com.mieai.qqbot.client;

import java.util.concurrent.CompletionStage;

/** Supplies a valid access token without exposing its raw value. */
@FunctionalInterface
public interface TokenProvider {
    CompletionStage<AccessToken> getAccessToken();

    /** Invalidates a token rejected by QQ. Implementations may ignore tokens that are no longer current. */
    default void invalidate(AccessToken rejectedToken) {
        // Stateless providers have nothing to invalidate.
    }
}
