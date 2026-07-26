package com.mieai.qqbot.client

import com.mieai.qqbot.protocol.auth.AccessTokenRequest
import com.mieai.qqbot.protocol.auth.AccessTokenResponse
import com.mieai.qqbot.protocol.json.JsonCodecs
import java.time.Clock
import java.time.DateTimeException
import java.util.concurrent.CompletionStage

/** JDK HTTP client for `POST /app/getAppAccessToken`. */
class QqAccessTokenClient(
    private val options: QqClientOptions,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenRequester {
    private val transport = JdkQqHttpTransport(JsonCodecs.defaultCodec(), options.requestTimeout)

    override fun requestToken(credentials: BotCredentials): CompletionStage<AccessToken> {
        val request = AccessTokenRequest(credentials.appId.value, credentials.appSecret.rawValue())
        return transport.postJson(options.tokenEndpoint, request, AccessTokenResponse::class.java)
            .thenApply(::toAccessToken)
    }

    private fun toAccessToken(response: AccessTokenResponse?): AccessToken {
        try {
            val payload = requireNotNull(response) { "response must not be null" }
            val accessToken = payload.accessToken
            if (accessToken == null || accessToken.isBlank()) {
                val responseCode = payload.code
                if (responseCode != null && responseCode != 0) {
                    throw QqClientException.authenticationRejected(options.tokenEndpoint, responseCode)
                }
                throw IllegalArgumentException("access_token must not be blank")
            }
            require(payload.expiresIn > 0L) { "expires_in must be positive" }
            val expiresAt = clock.instant().plusSeconds(payload.expiresIn)
            return AccessToken.of(accessToken, expiresAt)
        } catch (exception: QqClientException) {
            throw exception
        } catch (exception: IllegalArgumentException) {
            throw QqClientException.protocol(options.tokenEndpoint, exception)
        } catch (exception: DateTimeException) {
            throw QqClientException.protocol(options.tokenEndpoint, exception)
        }
    }
}
