package com.mieai.qqbot.app.plugin;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.plugins")
public class PluginRuntimeProperties {
    private boolean enabled = true;
    private Path directory = Path.of("/plugins");
    private Duration pollInterval = Duration.ofSeconds(1);
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration executionTimeout = Duration.ofSeconds(20);
    private int maxAttempts = 5;
    private int batchSize = 16;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Path getDirectory() { return directory; }
    public void setDirectory(Path directory) { this.directory = directory; }
    public Duration getPollInterval() { return pollInterval; }
    public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public Duration getExecutionTimeout() { return executionTimeout; }
    public void setExecutionTimeout(Duration executionTimeout) { this.executionTimeout = executionTimeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
