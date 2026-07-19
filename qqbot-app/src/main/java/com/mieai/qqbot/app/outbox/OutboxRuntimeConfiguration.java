package com.mieai.qqbot.app.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.app.gateway.GatewayRuntimeProperties;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRuntimeProperties.class)
public class OutboxRuntimeConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService outboxScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "qqbot-outbox-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "close")
    ProductionOutboxWorker productionOutboxWorker(OutboxRuntimeProperties properties,
            OutboxRepository outbox, BotRepository bots, AppSecretCipher secretCipher,
            GatewayRuntimeProperties gateway, ObjectMapper mapper,
            @Qualifier("outboxScheduler") ScheduledExecutorService scheduler) {
        return new ProductionOutboxWorker(outbox, bots, secretCipher,
                environment -> options(gateway, environment), mapper, scheduler, Clock.systemUTC(),
                properties.getPollInterval(), properties.getLeaseDuration(), properties.getRequestTimeout(),
                properties.getMaxAttempts(), properties.getBatchSize());
    }

    @Bean
    OutboxRuntimeLifecycle outboxRuntimeLifecycle(ProductionOutboxWorker worker, OutboxRuntimeProperties properties) {
        return new OutboxRuntimeLifecycle(worker, properties.isEnabled());
    }

    private static QqClientOptions options(GatewayRuntimeProperties properties, BotEnvironment environment) {
        return QqClientOptions.builder()
                .tokenEndpoint(properties.getTokenEndpoint())
                .openApiBaseUri(environment.isSandbox()
                        ? properties.getSandboxOpenApiBaseUri() : properties.getProductionOpenApiBaseUri())
                .requestTimeout(properties.getRequestTimeout())
                .tokenRefreshSkew(properties.getTokenRefreshSkew())
                .build();
    }
}
