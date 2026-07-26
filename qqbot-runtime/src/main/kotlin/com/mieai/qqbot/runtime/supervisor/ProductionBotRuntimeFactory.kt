package com.mieai.qqbot.runtime.supervisor

import com.mieai.qqbot.client.QqAccessTokenClient
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqOpenApiClient
import com.mieai.qqbot.client.SingleFlightTokenProvider
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.gateway.ExecutorGatewayScheduler
import com.mieai.qqbot.gateway.GatewayBackoffStrategy
import com.mieai.qqbot.gateway.GatewaySnapshotStore
import com.mieai.qqbot.gateway.GatewayTransport
import com.mieai.qqbot.gateway.JdkGatewayTransport
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/** Production factory for one isolated QQ Gateway runtime per persisted bot. */
class ProductionBotRuntimeFactory(
    secretCipher: AppSecretCipher,
    private val optionsResolver: (BotEnvironment) -> QqClientOptions,
    private val snapshotStoreFactory: (StoredBot) -> GatewaySnapshotStore,
    private val gatewayTransport: GatewayTransport = JdkGatewayTransport(),
    private val schedulerFactory: RuntimeSchedulerFactory =
        RuntimeSchedulerFactory { definition -> newScheduler(definition) },
    private val backoffStrategy: GatewayBackoffStrategy = GatewayBackoffStrategy.exponential(
        INITIAL_RECONNECT_DELAY,
        MAXIMUM_RECONNECT_DELAY,
        ::fullJitter,
    ),
) : BotRuntimeFactory {
    private val credentialDecryptor = BotCredentialDecryptor(secretCipher)

    override fun create(configuration: StoredBot, observer: BotRuntimeObserver): ManagedBotRuntime {
        val definition = configuration.definition
        val options = optionsResolver(definition.environment)
        val snapshotStore = snapshotStoreFactory(configuration)

        val credentials = credentialDecryptor.decrypt(configuration)
        var scheduler: RuntimeScheduler? = null
        try {
            scheduler = schedulerFactory.create(definition)
            val tokenClient = QqAccessTokenClient(options)
            val tokenProvider = SingleFlightTokenProvider(credentials, tokenClient, options)
            val openApiClient = QqOpenApiClient(options, tokenProvider, credentials.appId)
            return ProductionBotRuntime(
                definition,
                credentials,
                GatewayDiscovery { openApiClient.getGatewayBot() },
                tokenProvider,
                gatewayTransport,
                snapshotStore,
                scheduler,
                backoffStrategy,
                observer,
            )
        } catch (exception: RuntimeException) {
            closeQuietly(scheduler)
            credentials.close()
            throw exception
        }
    }

    private companion object {
        val INITIAL_RECONNECT_DELAY: Duration = Duration.ofSeconds(1)
        val MAXIMUM_RECONNECT_DELAY: Duration = Duration.ofSeconds(30)

        fun newScheduler(definition: BotDefinition): RuntimeScheduler {
            val delegate = ExecutorGatewayScheduler("qqbot-gateway-${definition.id}")
            return object : RuntimeScheduler {
                override fun schedule(delay: Duration, task: () -> Unit) =
                    delegate.schedule(delay, task)

                override fun close() = delegate.close()
            }
        }

        fun fullJitter(maximum: Duration): Duration {
            val bound = maximum.toNanos()
            if (bound == 0L) return Duration.ZERO
            return Duration.ofNanos(ThreadLocalRandom.current().nextLong(bound + 1L))
        }

        fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable == null) return
            try {
                closeable.close()
            } catch (_: Exception) {
                // A failed factory must still destroy the decrypted credential.
            }
        }
    }
}
