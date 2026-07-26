package com.mieai.qqbot.app.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.app.gateway.GatewayRuntimeProperties
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.runtime.outbox.ProductionOutboxWorker
import com.mieai.qqbot.runtime.security.AppSecretCipher
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRuntimeProperties::class)
class OutboxRuntimeConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    fun outboxScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "qqbot-outbox-worker").apply { isDaemon = true }
        }

    @Bean(destroyMethod = "close")
    fun productionOutboxWorker(
        properties: OutboxRuntimeProperties,
        outbox: OutboxRepository,
        bots: BotRepository,
        secretCipher: AppSecretCipher,
        botLeases: BotLeaseRepository,
        mediaStore: MediaAssetStore,
        gateway: GatewayRuntimeProperties,
        mapper: ObjectMapper,
        @Qualifier("qqbotInstanceId") instanceId: String,
        @Qualifier("outboxScheduler") scheduler: ScheduledExecutorService,
    ): ProductionOutboxWorker = ProductionOutboxWorker(
        outbox,
        bots,
        secretCipher,
        { environment -> options(gateway, environment) },
        mapper,
        scheduler,
        Clock.systemUTC(),
        properties.pollInterval,
        properties.leaseDuration,
        properties.requestTimeout,
        properties.maxAttempts,
        properties.batchSize,
        botLeases,
        instanceId,
        mediaStore,
    )

    @Bean
    fun outboxRuntimeLifecycle(
        worker: ProductionOutboxWorker,
        properties: OutboxRuntimeProperties,
    ) = OutboxRuntimeLifecycle(worker, properties.enabled)

    private fun options(
        properties: GatewayRuntimeProperties,
        environment: BotEnvironment,
    ): QqClientOptions = QqClientOptions(
        tokenEndpoint = properties.tokenEndpoint,
        openApiBaseUri = if (environment.isSandbox()) {
                properties.sandboxOpenApiBaseUri
            } else {
                properties.productionOpenApiBaseUri
            },
        requestTimeout = properties.requestTimeout,
        tokenRefreshSkew = properties.tokenRefreshSkew,
    )
}
