package com.mieai.qqbot.app.media;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.media")
public class MediaRuntimeProperties {
    private Path stagingDirectory = Path.of("media-staging");

    public Path getStagingDirectory() { return stagingDirectory; }
    public void setStagingDirectory(Path stagingDirectory) { this.stagingDirectory = stagingDirectory; }
}
