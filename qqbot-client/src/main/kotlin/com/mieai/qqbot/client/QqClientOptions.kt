package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.BotEnvironment
import java.net.URI
import java.time.Duration
import java.util.Locale

/** Network endpoints and time budgets used by the QQ clients. */
class QqClientOptions(
    tokenEndpoint: URI = DEFAULT_TOKEN_ENDPOINT,
    openApiBaseUri: URI = DEFAULT_OPEN_API_BASE_URI,
    val requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    val tokenRefreshSkew: Duration = DEFAULT_TOKEN_REFRESH_SKEW,
    val maxMediaBytes: Long = DEFAULT_MAX_MEDIA_BYTES,
    val mediaDownloadTimeout: Duration = DEFAULT_MEDIA_DOWNLOAD_TIMEOUT,
    val maxMediaRedirects: Int = DEFAULT_MAX_MEDIA_REDIRECTS,
) {
    val tokenEndpoint: URI = tokenEndpoint
    val openApiBaseUri: URI = normalizeBaseUri(openApiBaseUri)

    init {
        validateHttpUri(this.tokenEndpoint, "tokenEndpoint")
        validateHttpUri(this.openApiBaseUri, "openApiBaseUri")
        require(!requestTimeout.isZero && !requestTimeout.isNegative) { "requestTimeout must be positive" }
        require(!tokenRefreshSkew.isNegative) { "tokenRefreshSkew must not be negative" }
        require(maxMediaBytes in 1024L * 1024L..256L * 1024L * 1024L) {
            "maxMediaBytes must be between 1 and 256 MiB"
        }
        require(!mediaDownloadTimeout.isZero && !mediaDownloadTimeout.isNegative) {
            "mediaDownloadTimeout must be positive"
        }
        require(maxMediaRedirects in 0..8) { "maxMediaRedirects is invalid" }
    }

    fun copy(
        tokenEndpoint: URI = this.tokenEndpoint,
        openApiBaseUri: URI = this.openApiBaseUri,
        requestTimeout: Duration = this.requestTimeout,
        tokenRefreshSkew: Duration = this.tokenRefreshSkew,
        maxMediaBytes: Long = this.maxMediaBytes,
        mediaDownloadTimeout: Duration = this.mediaDownloadTimeout,
        maxMediaRedirects: Int = this.maxMediaRedirects,
    ): QqClientOptions = QqClientOptions(
        tokenEndpoint = tokenEndpoint,
        openApiBaseUri = openApiBaseUri,
        requestTimeout = requestTimeout,
        tokenRefreshSkew = tokenRefreshSkew,
        maxMediaBytes = maxMediaBytes,
        mediaDownloadTimeout = mediaDownloadTimeout,
        maxMediaRedirects = maxMediaRedirects,
    )

    companion object {
        val DEFAULT_TOKEN_ENDPOINT: URI = URI.create("https://bots.qq.com/app/getAppAccessToken")
        val DEFAULT_OPEN_API_BASE_URI: URI = URI.create("https://api.sgroup.qq.com/")
        val DEFAULT_SANDBOX_OPEN_API_BASE_URI: URI = URI.create("https://sandbox.api.sgroup.qq.com/")
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(10)
        val DEFAULT_TOKEN_REFRESH_SKEW: Duration = Duration.ofSeconds(60)
        val DEFAULT_MAX_MEDIA_BYTES: Long = 16L * 1024L * 1024L
        val DEFAULT_MEDIA_DOWNLOAD_TIMEOUT: Duration = Duration.ofSeconds(15)
        val DEFAULT_MAX_MEDIA_REDIRECTS: Int = 3

        fun forEnvironment(environment: BotEnvironment): QqClientOptions = QqClientOptions(
            openApiBaseUri = if (environment.isSandbox()) {
                DEFAULT_SANDBOX_OPEN_API_BASE_URI
            } else {
                DEFAULT_OPEN_API_BASE_URI
            },
        )

        private fun validateHttpUri(uri: URI, name: String) {
            val scheme = uri.scheme
            require(uri.isAbsolute && scheme != null && uri.host != null) {
                "$name must be an absolute HTTP(S) URI with a host"
            }
            val normalizedScheme = scheme.lowercase(Locale.ROOT)
            require(normalizedScheme == "http" || normalizedScheme == "https") {
                "$name must use HTTP or HTTPS"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "$name must not contain user info, query, or fragment"
            }
        }

        private fun normalizeBaseUri(value: URI): URI {
            val text = value.toString()
            return if (text.endsWith('/')) value else URI.create("$text/")
        }
    }
}
