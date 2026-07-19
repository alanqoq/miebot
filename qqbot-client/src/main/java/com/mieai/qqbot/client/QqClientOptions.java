package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Network endpoints and time budgets used by the QQ clients. */
public final class QqClientOptions {
    public static final URI DEFAULT_TOKEN_ENDPOINT =
            URI.create("https://bots.qq.com/app/getAppAccessToken");
    public static final URI DEFAULT_OPEN_API_BASE_URI = URI.create("https://api.sgroup.qq.com/");
    public static final URI DEFAULT_SANDBOX_OPEN_API_BASE_URI =
            URI.create("https://sandbox.api.sgroup.qq.com/");
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_TOKEN_REFRESH_SKEW = Duration.ofSeconds(60);

    private final URI tokenEndpoint;
    private final URI openApiBaseUri;
    private final Duration requestTimeout;
    private final Duration tokenRefreshSkew;

    private QqClientOptions(Builder builder) {
        tokenEndpoint = validateHttpUri(builder.tokenEndpoint, "tokenEndpoint");
        openApiBaseUri = normalizeBaseUri(validateHttpUri(builder.openApiBaseUri, "openApiBaseUri"));
        requestTimeout = validatePositive(builder.requestTimeout, "requestTimeout");
        tokenRefreshSkew = validateNonNegative(builder.tokenRefreshSkew, "tokenRefreshSkew");
    }

    public static QqClientOptions defaults() {
        return builder().build();
    }

    public static QqClientOptions defaults(BotEnvironment environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        return environment.isSandbox()
                ? builder().openApiBaseUri(DEFAULT_SANDBOX_OPEN_API_BASE_URI).build()
                : defaults();
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI tokenEndpoint() {
        return tokenEndpoint;
    }

    public URI openApiBaseUri() {
        return openApiBaseUri;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration tokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    public static final class Builder {
        private URI tokenEndpoint = DEFAULT_TOKEN_ENDPOINT;
        private URI openApiBaseUri = DEFAULT_OPEN_API_BASE_URI;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration tokenRefreshSkew = DEFAULT_TOKEN_REFRESH_SKEW;

        private Builder() {}

        public Builder tokenEndpoint(URI value) {
            tokenEndpoint = value;
            return this;
        }

        public Builder openApiBaseUri(URI value) {
            openApiBaseUri = value;
            return this;
        }

        public Builder requestTimeout(Duration value) {
            requestTimeout = value;
            return this;
        }

        public Builder tokenRefreshSkew(Duration value) {
            tokenRefreshSkew = value;
            return this;
        }

        public QqClientOptions build() {
            return new QqClientOptions(this);
        }
    }

    private static URI validateHttpUri(URI value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String scheme = value.getScheme();
        if (!value.isAbsolute() || scheme == null || value.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URI with a host");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            throw new IllegalArgumentException(name + " must use HTTP or HTTPS");
        }
        if (value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException(name + " must not contain user info, query, or fragment");
        }
        return value;
    }

    private static URI normalizeBaseUri(URI value) {
        String text = value.toString();
        return text.endsWith("/") ? value : URI.create(text + "/");
    }

    private static Duration validatePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration validateNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
