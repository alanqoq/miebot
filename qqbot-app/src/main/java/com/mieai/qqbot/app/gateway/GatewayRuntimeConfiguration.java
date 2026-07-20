package com.mieai.qqbot.app.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.gateway.GatewaySnapshotStore;
import com.mieai.qqbot.gateway.GatewayTransport;
import com.mieai.qqbot.gateway.JdkGatewayTransport;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFactory;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.runtime.supervisor.ProductionBotRuntimeFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRuntimeProperties.class)
public class GatewayRuntimeConfiguration {

    @Bean("qqbotInstanceId")
    String qqbotInstanceId(GatewayRuntimeProperties properties) {
        String configured = properties.getInstanceId();
        return configured == null || configured.isBlank()
                ? "qqbot-" + java.util.UUID.randomUUID() : configured.strip();
    }

    @Bean
    GatewayTransport gatewayTransport(GatewayRuntimeProperties properties) {
        return new JdkGatewayTransport(
                properties.getConnectTimeout(), properties.getMaxTextCharacters());
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService gatewaySnapshotExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qqbot-gateway-snapshot-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean
    BotRuntimeFactory productionBotRuntimeFactory(
            AppSecretCipher appSecretCipher,
            GatewayTransport gatewayTransport,
            GatewayRuntimeProperties properties,
            ObjectMapper objectMapper,
            @Qualifier("gatewaySnapshotExecutor") ExecutorService snapshotExecutor) {
        Path sessionDirectory = properties.getSessionDirectory().toAbsolutePath().normalize();
        return new ProductionBotRuntimeFactory(
                appSecretCipher,
                environment -> clientOptions(properties, environment),
                gatewayTransport,
                storedBot -> snapshotStore(
                        sessionDirectory, storedBot, objectMapper, snapshotExecutor));
    }

    @Bean(destroyMethod = "close")
    BotSupervisor botSupervisor(
            BotRepository botRepository,
            BotRuntimeFactory productionBotRuntimeFactory,
            GatewayRuntimeProperties properties,
            EventInboxRepository eventInboxRepository,
            BotLeaseRepository botLeaseRepository,
            @org.springframework.beans.factory.annotation.Qualifier("qqbotInstanceId") String instanceId,
            Pf4jPluginHost pluginHost) {
        return new BotSupervisor(
                botRepository,
                productionBotRuntimeFactory,
                properties.getReconcileInterval(),
                properties.getShutdownTimeout(),
                Clock.systemUTC(),
                eventInboxRepository,
                botLeaseRepository,
                instanceId,
                properties.getLeaseDuration(),
                pluginHost::loadedPluginHashes);
    }

    @Bean
    BotSupervisorLifecycle botSupervisorLifecycle(
            BotSupervisor botSupervisor, GatewayRuntimeProperties properties) {
        return new BotSupervisorLifecycle(botSupervisor, properties.isEnabled());
    }

    private static QqClientOptions clientOptions(
            GatewayRuntimeProperties properties, BotEnvironment environment) {
        return QqClientOptions.builder()
                .tokenEndpoint(properties.getTokenEndpoint())
                .openApiBaseUri(environment.isSandbox()
                        ? properties.getSandboxOpenApiBaseUri()
                        : properties.getProductionOpenApiBaseUri())
                .requestTimeout(properties.getRequestTimeout())
                .tokenRefreshSkew(properties.getTokenRefreshSkew())
                .build();
    }

    private static GatewaySnapshotStore snapshotStore(
            Path sessionDirectory,
            StoredBot storedBot,
            ObjectMapper objectMapper,
            ExecutorService executor) {
        BotDefinition definition = storedBot.definition();
        String fileName = definition.id()
                + "-shard-"
                + definition.shardSpec().index()
                + ".json";
        return new FileGatewaySnapshotStore(
                sessionDirectory.resolve(fileName),
                snapshotFingerprint(definition),
                objectMapper,
                executor);
    }

    private static String snapshotFingerprint(BotDefinition definition) {
        String material = String.join(
                "\n",
                definition.id().toString(),
                Long.toString(definition.revision().value()),
                definition.appId().value(),
                definition.environment().name(),
                Long.toString(definition.intents().bits()),
                Integer.toString(definition.shardSpec().index()),
                Integer.toString(definition.shardSpec().count()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
