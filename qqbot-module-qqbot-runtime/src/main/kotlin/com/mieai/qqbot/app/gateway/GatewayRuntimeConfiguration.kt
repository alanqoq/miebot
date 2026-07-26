package com.mieai.qqbot.app.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.gateway.GatewaySnapshotStore
import com.mieai.qqbot.gateway.GatewayTransport
import com.mieai.qqbot.gateway.JdkGatewayTransport
import com.mieai.qqbot.modules.runtime.RobotPluginArtifactHashes
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.runtime.event.BotGatewayEventSink
import com.mieai.qqbot.runtime.event.DefaultBotGatewayEventBus
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider
import com.mieai.qqbot.runtime.openapi.ProductionBotOpenApiClientProvider
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.supervisor.BotRuntimeFactory
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import com.mieai.qqbot.runtime.supervisor.ProductionBotRuntimeFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Clock
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayRuntimeProperties::class)
class GatewayRuntimeConfiguration {
    @Bean("qqbotInstanceId")
    fun qqbotInstanceId(properties: GatewayRuntimeProperties): String {
        val configured = properties.instanceId
        return if (configured.isNullOrBlank()) {
            "qqbot-${UUID.randomUUID()}"
        } else {
            configured.trim()
        }
    }

    @Bean
    fun gatewayTransport(properties: GatewayRuntimeProperties): GatewayTransport =
        JdkGatewayTransport(properties.connectTimeout, properties.maxTextCharacters)

    @Bean(destroyMethod = "shutdownNow")
    fun gatewaySnapshotExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qqbot-gateway-snapshot-store").apply { isDaemon = true }
        }

    @Bean
    fun productionBotRuntimeFactory(
        appSecretCipher: AppSecretCipher,
        gatewayTransport: GatewayTransport,
        properties: GatewayRuntimeProperties,
        objectMapper: ObjectMapper,
        @Qualifier("gatewaySnapshotExecutor") snapshotExecutor: ExecutorService,
    ): BotRuntimeFactory {
        val sessionDirectory = properties.sessionDirectory.toAbsolutePath().normalize()
        return ProductionBotRuntimeFactory(
            appSecretCipher,
            { environment -> clientOptions(properties, environment) },
            { storedBot ->
                snapshotStore(sessionDirectory, storedBot, objectMapper, snapshotExecutor)
            },
            gatewayTransport = gatewayTransport,
        )
    }

    @Bean(destroyMethod = "close")
    fun botOpenApiClientProvider(
        botRepository: BotRepository,
        appSecretCipher: AppSecretCipher,
        properties: GatewayRuntimeProperties,
    ): BotOpenApiClientProvider = ProductionBotOpenApiClientProvider(
        botRepository,
        appSecretCipher,
        { environment -> clientOptions(properties, environment) },
    )

    @Bean(destroyMethod = "close")
    fun botGatewayEventBus(): DefaultBotGatewayEventBus = DefaultBotGatewayEventBus()

    @Bean(destroyMethod = "close")
    fun botSupervisor(
        botRepository: BotRepository,
        productionBotRuntimeFactory: BotRuntimeFactory,
        properties: GatewayRuntimeProperties,
        eventInboxRepository: EventInboxRepository,
        botLeaseRepository: BotLeaseRepository,
        botGatewayEventSink: BotGatewayEventSink,
        @Qualifier("qqbotInstanceId") instanceId: String,
        pluginArtifactHashes: ObjectProvider<RobotPluginArtifactHashes>,
    ): BotSupervisor = BotSupervisor(
        botRepository,
        productionBotRuntimeFactory,
        reconciliationInterval = properties.reconcileInterval,
        shutdownTimeout = properties.shutdownTimeout,
        clock = Clock.systemUTC(),
        inboxRepository = eventInboxRepository,
        leaseRepository = botLeaseRepository,
        leaseOwnerId = instanceId,
        leaseDuration = properties.leaseDuration,
        pluginHashes = {
            pluginArtifactHashes.getIfAvailable { RobotPluginArtifactHashes.empty() }.current()
        },
        gatewayEvents = botGatewayEventSink,
    )

    @Bean
    fun botSupervisorLifecycle(
        botSupervisor: BotSupervisor,
        properties: GatewayRuntimeProperties,
    ): BotSupervisorLifecycle = BotSupervisorLifecycle(botSupervisor, properties.enabled)

    private fun clientOptions(
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

    private fun snapshotStore(
        sessionDirectory: Path,
        storedBot: StoredBot,
        objectMapper: ObjectMapper,
        executor: ExecutorService,
    ): GatewaySnapshotStore {
        val definition = storedBot.definition
        val fileName = "${definition.id}-shard-${definition.shardSpec.index}.json"
        return FileGatewaySnapshotStore(
            sessionDirectory.resolve(fileName),
            snapshotFingerprint(definition),
            objectMapper,
            executor,
        )
    }

    private fun snapshotFingerprint(definition: BotDefinition): String {
        val material = listOf(
            definition.id.toString(),
            definition.revision.value.toString(),
            definition.appId.value,
            definition.environment.name,
            definition.intents.bits.toString(),
            definition.shardSpec.index.toString(),
            definition.shardSpec.count.toString(),
        ).joinToString("\n")
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(material.toByteArray(StandardCharsets.UTF_8))
            HexFormat.of().formatHex(digest)
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is unavailable", exception)
        }
    }
}
