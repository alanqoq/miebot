package com.mieai.qqbot.client;

import com.mieai.qqbot.protocol.auth.AccessTokenRequest;
import com.mieai.qqbot.protocol.auth.AccessTokenResponse;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** JDK HTTP client for {@code POST /app/getAppAccessToken}. */
public final class QqAccessTokenClient implements AccessTokenRequester {
    private final QqClientOptions options;
    private final JdkQqHttpTransport transport;
    private final Clock clock;

    public QqAccessTokenClient(QqClientOptions options) {
        this(options, Clock.systemUTC());
    }

    QqAccessTokenClient(QqClientOptions options, Clock clock) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        transport = new JdkQqHttpTransport(JsonCodecs.defaultCodec(), options.requestTimeout());
    }

    @Override
    public CompletionStage<AccessToken> requestToken(BotCredentials credentials) {
        Objects.requireNonNull(credentials, "credentials must not be null");
        AccessTokenRequest request = new AccessTokenRequest(
                credentials.appId().value(), credentials.appSecret().rawValue());
        return transport
                .postJson(options.tokenEndpoint(), request, AccessTokenResponse.class)
                .thenApply(this::toAccessToken);
    }

    private AccessToken toAccessToken(AccessTokenResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        try {
            if (response.accessToken() == null || response.accessToken().isBlank()) {
                if (response.code() != null && response.code() != 0) {
                    throw QqClientException.authenticationRejected(
                            options.tokenEndpoint(), response.code());
                }
                throw new IllegalArgumentException("access_token must not be blank");
            }
            if (response.expiresIn() <= 0L) {
                throw new IllegalArgumentException("expires_in must be positive");
            }
            Instant expiresAt = clock.instant().plusSeconds(response.expiresIn());
            return AccessToken.of(response.accessToken(), expiresAt);
        } catch (QqClientException exception) {
            throw exception;
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw QqClientException.protocol(options.tokenEndpoint(), exception);
        }
    }
}
