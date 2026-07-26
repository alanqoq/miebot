package com.mieai.qqbot.app.gateway

import com.mieai.qqbot.client.QqClientOptions
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.gateway")
class GatewayRuntimeProperties {
    var enabled: Boolean = true
    var sessionDirectory: Path = Path.of("gateway-sessions")
    var reconcileInterval: Duration = Duration.ofSeconds(15)
    var leaseDuration: Duration = Duration.ofSeconds(45)
    var instanceId: String? = null
    var shutdownTimeout: Duration = Duration.ofSeconds(10)
    var connectTimeout: Duration = Duration.ofSeconds(10)
    var requestTimeout: Duration = QqClientOptions.DEFAULT_REQUEST_TIMEOUT
    var tokenRefreshSkew: Duration = QqClientOptions.DEFAULT_TOKEN_REFRESH_SKEW
    var tokenEndpoint: URI = QqClientOptions.DEFAULT_TOKEN_ENDPOINT
    var productionOpenApiBaseUri: URI = QqClientOptions.DEFAULT_OPEN_API_BASE_URI
    var sandboxOpenApiBaseUri: URI = QqClientOptions.DEFAULT_SANDBOX_OPEN_API_BASE_URI
    var maxTextCharacters: Int = 2 * 1024 * 1024
}
