package com.mieai.qqbot.onebot11;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.onebot11")
public class OneBot11Properties {
    private Path cacheDirectory = Path.of("onebot-cache");

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }
}
