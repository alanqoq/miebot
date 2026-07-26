package com.mieai.qqbot.client

import java.util.concurrent.CompletionStage

/** Supplies a valid access token without exposing its raw value. */
fun interface TokenProvider {
    fun getAccessToken(): CompletionStage<AccessToken>

    /** Invalidates a token rejected by QQ. Implementations may ignore tokens that are no longer current. */
    fun invalidate(rejectedToken: AccessToken) {
        // Stateless providers have nothing to invalidate.
    }
}
