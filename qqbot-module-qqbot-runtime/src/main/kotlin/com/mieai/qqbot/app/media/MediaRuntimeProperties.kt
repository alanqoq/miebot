package com.mieai.qqbot.app.media

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.media")
class MediaRuntimeProperties {
    var stagingDirectory: Path = Path.of("media-staging")
}
