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
    public static final long DEFAULT_MAX_MEDIA_BYTES = 16L * 1024L * 1024L;
    public static final Duration DEFAULT_MEDIA_DOWNLOAD_TIMEOUT = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_MEDIA_REDIRECTS = 3;

    private final URI tokenEndpoint;
    private final URI openApiBaseUri;
    private final Duration requestTimeout;
    private final Duration tokenRefreshSkew;
    private final long maxMediaBytes;
    private final Duration mediaDownloadTimeout;
    private final int maxMediaRedirects;

    private QqClientOptions(Builder builder) {
        tokenEndpoint = validateHttpUri(builder.tokenEndpoint, "tokenEndpoint");
        openApiBaseUri = normalizeBaseUri(validateHttpUri(builder.openApiBaseUri, "openApiBaseUri"));
        requestTimeout = validatePositive(builder.requestTimeout, "requestTimeout");
        tokenRefreshSkew = validateNonNegative(builder.tokenRefreshSkew, "tokenRefreshSkew");
        if (builder.maxMediaBytes < 1024L * 1024L
                || builder.maxMediaBytes > 256L * 1024L * 1024L) {
            throw new IllegalArgumentException("maxMediaBytes must be between 1 and 256 MiB");
        }
        maxMediaBytes = builder.maxMediaBytes;
        mediaDownloadTimeout = validatePositive(builder.mediaDownloadTimeout, "mediaDownloadTimeout");
        if (builder.maxMediaRedirects < 0 || builder.maxMediaRedirects > 8) {
            throw new IllegalArgumentException("maxMediaRedirects is invalid");
        }
        maxMediaRedirects = builder.maxMediaRedirects;
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

    public long maxMediaBytes() { return maxMediaBytes; }
    public Duration mediaDownloadTimeout() { return mediaDownloadTimeout; }
    public int maxMediaRedirects() { return maxMediaRedirects; }

    public static final class Builder {
        private URI tokenEndpoint = DEFAULT_TOKEN_ENDPOINT;
        private URI openApiBaseUri = DEFAULT_OPEN_API_BASE_URI;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private Duration tokenRefreshSkew = DEFAULT_TOKEN_REFRESH_SKEW;
        private long maxMediaBytes = DEFAULT_MAX_MEDIA_BYTES;
        private Duration mediaDownloadTimeout = DEFAULT_MEDIA_DOWNLOAD_TIMEOUT;
        private int maxMediaRedirects = DEFAULT_MAX_MEDIA_REDIRECTS;

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

        public Builder maxMediaBytes(long value) { maxMediaBytes = value; return this; }
        public Builder mediaDownloadTimeout(Duration value) { mediaDownloadTimeout = value; return this; }
        public Builder maxMediaRedirects(int value) { maxMediaRedirects = value; return this; }

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
