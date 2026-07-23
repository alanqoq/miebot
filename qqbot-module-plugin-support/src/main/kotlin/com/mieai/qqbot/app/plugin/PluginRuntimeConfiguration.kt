package com.mieai.qqbot.app.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.admin.plugins.PluginBindingFileService
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PluginRuntimeProperties::class)
class PluginRuntimeConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    fun pluginScheduler(): ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "qqbot-plugin-worker").apply { isDaemon = true }
    }

    @Bean(destroyMethod = "close")
    fun pf4jPluginHost(
        properties: PluginRuntimeProperties,
        artifacts: PluginArtifactRepository,
        bots: BotRepository,
        outbox: OutboxRepository,
        storage: PluginStorageRepository,
        mediaAssetStore: MediaAssetStore,
        objectMapper: ObjectMapper,
    ) = Pf4jPluginHost(
        properties.directory,
        properties.dataDirectory,
        artifacts,
        bots,
        outbox,
        storage,
        mediaAssetStore,
        objectMapper,
        Clock.systemUTC(),
        properties.bindingQueueCapacity,
        properties.shutdownTimeout,
    )

    @Bean(destroyMethod = "close")
    fun pluginRuntimeService(
        properties: PluginRuntimeProperties,
        host: Pf4jPluginHost,
        inbox: EventInboxRepository,
        bindings: BotPluginBindingRepository,
        deliveries: PluginDeliveryRepository,
        botLeases: BotLeaseRepository,
        @Qualifier("qqbotInstanceId") instanceId: String,
        @Qualifier("pluginScheduler") pluginScheduler: ScheduledExecutorService,
    ) = PluginRuntimeService(
        host,
        inbox,
        bindings,
        deliveries,
        pluginScheduler,
        Clock.systemUTC(),
        properties.pollInterval,
        properties.leaseDuration,
        properties.executionTimeout,
        properties.cancellationGrace,
        properties.maxAttempts,
        properties.batchSize,
        botLeases,
        instanceId,
    )

    @Bean
    fun pluginRuntimeLifecycle(
        runtime: PluginRuntimeService,
        host: Pf4jPluginHost,
        files: PluginBindingFileService,
        properties: PluginRuntimeProperties,
    ) = PluginRuntimeLifecycle(runtime, host, files, properties.enabled)
}
