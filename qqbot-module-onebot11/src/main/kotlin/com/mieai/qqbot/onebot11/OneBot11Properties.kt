package com.mieai.qqbot.onebot11

import java.nio.file.Path
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("qqbot.onebot11")
class OneBot11Properties {
    var cacheDirectory: Path = Path.of("onebot-cache")
}
