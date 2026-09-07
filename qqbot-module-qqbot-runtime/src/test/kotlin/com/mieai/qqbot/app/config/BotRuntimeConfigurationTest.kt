package com.mieai.qqbot.app.config

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.app.outbox.OutboxRuntimeProperties
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.configuration.CreateBotCommand
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher
import com.mieai.qqbot.runtime.security.AppSecret
import com.mieai.qqbot.runtime.security.AppSecretBinding
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.security.KeyProvider
import com.mieai.qqbot.runtime.security.KeyUnavailableException
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.time.Duration

class BotRuntimeConfigurationTest {
    private val configuration = BotRuntimeConfiguration()

    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `outbox defaults cover the sixty second QQ request budget`() {
        val properties = OutboxRuntimeProperties()
        assertThat(properties.requestTimeout).isEqualTo(Duration.ofSeconds(60))
        assertThat(properties.leaseDuration).isEqualTo(Duration.ofSeconds(90))
    }

    @Test fun `keeps application available when master key is not configured`() {
        val provider = configuration.appSecretKeyProvider(AppSecretEncryptionProperties())

        assertThatThrownBy(provider::activeKey)
            .isInstanceOf(KeyUnavailableException::class.java)
            .hasMessageContaining("unavailable")
    }

    @Test fun `loads a configured base64 aes256 key`() {
        val properties = AppSecretEncryptionProperties()
        properties.keyId = "master-v1"
        properties.masterKey = Base64.getEncoder().encodeToString(ByteArray(32))

        val provider = configuration.appSecretKeyProvider(properties)

        assertThat(provider.activeKey().keyId).isEqualTo("master-v1")
        assertThat(provider.toString()).contains("configured=true").contains("<redacted>")
    }

    @Test fun `reads a key from a secret file`() {
        val keyFile = temporaryDirectory.resolve("app-secret.key")
        val existingKey = Base64.getEncoder().encodeToString(ByteArray(32)) + System.lineSeparator()
        Files.writeString(keyFile, existingKey)
        val properties = AppSecretEncryptionProperties()
        properties.masterKeyFile = keyFile.toString()

        assertThat(configuration.appSecretKeyProvider(properties).activeKey().keyId).isEqualTo("primary")
        assertThat(Files.readString(keyFile)).isEqualTo(existingKey)
    }

    @Test fun `creates a key and missing parent directories for first use`() {
        val keyFile = temporaryDirectory.resolve("nested/secrets/app-secret.key")
        val properties = AppSecretEncryptionProperties()
        properties.masterKeyFile = keyFile.toString()

        val provider = configuration.appSecretKeyProvider(properties)

        assertThat(provider.activeKey().keyId).isEqualTo("primary")
        assertThat(Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII))).hasSize(32)
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(keyFile)).isEqualTo(PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test fun `concurrent first use shares the single created key`() {
        val keyFile = temporaryDirectory.resolve("concurrent/app-secret.key")
        val properties = AppSecretEncryptionProperties()
        properties.keyId = "concurrent-v1"
        properties.masterKeyFile = keyFile.toString()
        val workerCount = 12
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val executor: ExecutorService = Executors.newFixedThreadPool(workerCount)
        val futures = ArrayList<Future<KeyProvider>>()
        try {
            repeat(workerCount) {
                futures += executor.submit<KeyProvider> {
                    ready.countDown()
                    start.await()
                    configuration.appSecretKeyProvider(properties)
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val providers = futures.map { it.get(5, TimeUnit.SECONDS) }
            val binding = AppSecretBinding(
                BotId.parse("550e8400-e29b-41d4-a716-446655440000"),
                QqAppId.of("102012345"),
                BotEnvironment.SANDBOX,
            )
            val encrypted: SecretCiphertext
            AppSecret.of("concurrent-secret").use { secret ->
                encrypted = AesGcmAppSecretCipher(providers.first()).encrypt(secret, binding)
            }
            providers.forEach { provider ->
                val cipher = AesGcmAppSecretCipher(provider)
                assertThatCode {
                    cipher.decrypt(encrypted, binding).use { decrypted ->
                        assertThat(decrypted.isDestroyed).isFalse()
                    }
                }.doesNotThrowAnyException()
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `explicit key takes priority without touching the configured file`() {
        val keyFile = temporaryDirectory.resolve("must-not-be-created/app-secret.key")
        val properties = AppSecretEncryptionProperties()
        properties.masterKey = Base64.getEncoder().encodeToString(ByteArray(32))
        properties.masterKeyFile = keyFile.toString()

        assertThat(configuration.appSecretKeyProvider(properties).activeKey().keyId).isEqualTo("primary")
        assertThat(keyFile).doesNotExist()
    }

    @Test fun `rejects invalid key configuration`() {
        val invalidBase64 = AppSecretEncryptionProperties()
        invalidBase64.masterKey = "not base64"
        assertThatIllegalStateException()
            .isThrownBy { configuration.appSecretKeyProvider(invalidBase64) }
            .withMessageContaining("Base64")

        val shortKey = AppSecretEncryptionProperties()
        shortKey.masterKey = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThatThrownBy { configuration.appSecretKeyProvider(shortKey) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("256 bits")
    }

    @Test fun `configuration service publishes create enable and disable to supervisor`() {
        val stored = AtomicReference<StoredBot>()
        val repository = mock(BotRepository::class.java)
        val fallbackStored = mock(StoredBot::class.java)
        val fallbackBotId = BotId.parse("550e8400-e29b-41d4-a716-446655440099")
        val fallbackRevision = BotRevision.initial()
        doAnswer { invocation ->
            stored.set(invocation.getArgument(0))
            null
        }.`when`(repository).insert(anyValue(StoredBot::class.java, fallbackStored))
        `when`(repository.findById(anyValue(BotId::class.java, fallbackBotId)))
            .thenAnswer { _ -> stored.get() }
        `when`(
            repository.update(
                anyValue(StoredBot::class.java, fallbackStored),
                anyValue(BotRevision::class.java, fallbackRevision),
            ),
        )
            .thenAnswer { invocation ->
                val desired = invocation.getArgument<StoredBot>(0)
                val next = invocation.getArgument<BotRevision>(1).next()
                val definition = desired.definition
                stored.set(
                    StoredBot(
                        BotDefinition(
                            definition.id,
                            definition.displayName,
                            definition.appId,
                            definition.environment,
                            definition.intents,
                            definition.shardSpec,
                            definition.enabled,
                            next,
                            definition.createdAt,
                            definition.updatedAt,
                        ),
                        desired.appSecret,
                    ),
                )
                next
            }
        val cipher: AppSecretCipher = AesGcmAppSecretCipher(StaticKeyProvider.configured("test-key", ByteArray(32)))
        val supervisor = mock(BotSupervisor::class.java)
        val pluginListener = mock(BotConfigurationChangeListener::class.java)
        val service: BotConfigurationService = configuration.botConfigurationService(repository, cipher, listOf(supervisor, pluginListener))

        val created = AppSecret.of("runtime-listener-secret").use { secret ->
            service.create(
                CreateBotCommand(
                    "Runtime Listener Bot",
                    QqAppId.of("1905208810"),
                    BotEnvironment.PRODUCTION,
                    GatewayIntents.of(1L shl 25),
                    ShardSpec.single(),
                    false,
                    secret,
                ),
            )
        }
        val enabled = service.enable(created.id, created.revision)
        service.disable(enabled.id, enabled.revision)

        val changes = ArgumentCaptor.forClass(BotConfigurationChange::class.java)
        val fallbackChange = BotConfigurationChange(
            fallbackBotId,
            fallbackRevision,
            false,
            BotConfigurationChangeKind.CREATED,
        )
        verify(supervisor, times(3)).onCommitted(changes.capture() ?: fallbackChange)
        verify(pluginListener, times(3)).onCommitted(anyValue(BotConfigurationChange::class.java, fallbackChange))
        assertThat(changes.allValues.map { it.kind })
            .containsExactly(BotConfigurationChangeKind.CREATED, BotConfigurationChangeKind.ENABLED, BotConfigurationChangeKind.DISABLED)
        assertThat(changes.allValues.map { it.enabled })
            .containsExactly(false, true, false)
    }

    private fun <T : Any> anyValue(type: Class<T>, fallback: T): T = any(type) ?: fallback
}
