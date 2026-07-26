package com.mieai.qqbot.app.outbox

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.outbox")
class OutboxRuntimeProperties {
    var enabled: Boolean = true
    var pollInterval: Duration = Duration.ofSeconds(1)
    var leaseDuration: Duration = Duration.ofSeconds(45)
    var requestTimeout: Duration = Duration.ofSeconds(20)
    var maxAttempts: Int = 8
    var batchSize: Int = 16
}
