package com.mieai.qqbot.app.gateway;

import com.mieai.qqbot.client.QqClientOptions;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qqbot.gateway")
public class GatewayRuntimeProperties {
    private boolean enabled = true;
    private Path sessionDirectory = Path.of("gateway-sessions");
    private Duration reconcileInterval = Duration.ofSeconds(15);
    private Duration leaseDuration = Duration.ofSeconds(45);
    private String instanceId;
    private Duration shutdownTimeout = Duration.ofSeconds(10);
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = QqClientOptions.DEFAULT_REQUEST_TIMEOUT;
    private Duration tokenRefreshSkew = QqClientOptions.DEFAULT_TOKEN_REFRESH_SKEW;
    private URI tokenEndpoint = QqClientOptions.DEFAULT_TOKEN_ENDPOINT;
    private URI productionOpenApiBaseUri = QqClientOptions.DEFAULT_OPEN_API_BASE_URI;
    private URI sandboxOpenApiBaseUri = QqClientOptions.DEFAULT_SANDBOX_OPEN_API_BASE_URI;
    private int maxTextCharacters = 2 * 1024 * 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getSessionDirectory() {
        return sessionDirectory;
    }

    public void setSessionDirectory(Path sessionDirectory) {
        this.sessionDirectory = sessionDirectory;
    }

    public Duration getReconcileInterval() {
        return reconcileInterval;
    }

    public void setReconcileInterval(Duration reconcileInterval) {
        this.reconcileInterval = reconcileInterval;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public Duration getTokenRefreshSkew() {
        return tokenRefreshSkew;
    }

    public void setTokenRefreshSkew(Duration tokenRefreshSkew) {
        this.tokenRefreshSkew = tokenRefreshSkew;
    }

    public URI getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(URI tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public URI getProductionOpenApiBaseUri() {
        return productionOpenApiBaseUri;
    }

    public void setProductionOpenApiBaseUri(URI productionOpenApiBaseUri) {
        this.productionOpenApiBaseUri = productionOpenApiBaseUri;
    }

    public URI getSandboxOpenApiBaseUri() {
        return sandboxOpenApiBaseUri;
    }

    public void setSandboxOpenApiBaseUri(URI sandboxOpenApiBaseUri) {
        this.sandboxOpenApiBaseUri = sandboxOpenApiBaseUri;
    }

    public int getMaxTextCharacters() {
        return maxTextCharacters;
    }

    public void setMaxTextCharacters(int maxTextCharacters) {
        this.maxTextCharacters = maxTextCharacters;
    }
}
