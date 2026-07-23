package com.mieai.qqbot.app.plugin

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("qqbot.plugins")
class PluginRuntimeProperties {
    var enabled: Boolean = true
    var directory: Path = Path.of("/plugins")
    var dataDirectory: Path = Path.of("/data/plugin-data")
    var pollInterval: Duration = Duration.ofSeconds(1)
    var leaseDuration: Duration = Duration.ofSeconds(30)
    var executionTimeout: Duration = Duration.ofSeconds(20)
    var cancellationGrace: Duration = Duration.ofSeconds(5)
    var maxAttempts: Int = 5
    var batchSize: Int = 16
    var bindingQueueCapacity: Int = 256
    var shutdownTimeout: Duration = Duration.ofSeconds(20)
}
