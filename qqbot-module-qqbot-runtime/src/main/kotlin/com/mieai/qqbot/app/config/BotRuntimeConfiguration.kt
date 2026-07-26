package com.mieai.qqbot.app.config

import com.mieai.qqbot.persistence.admin.AdminUserRepository
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository
import com.mieai.qqbot.persistence.audit.AuditLogRepository
import com.mieai.qqbot.persistence.audit.JdbcAuditLogRepository
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.JdbcBotRepository
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginDeliveryRepository
import com.mieai.qqbot.persistence.plugin.JdbcPluginStorageRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.security.KeyProvider
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.locks.LockSupport
import javax.sql.DataSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppSecretEncryptionProperties::class)
class BotRuntimeConfiguration {
    @Bean
    fun botRepository(dataSource: DataSource): BotRepository = JdbcBotRepository(dataSource)

    @Bean
    fun adminUserRepository(dataSource: DataSource): AdminUserRepository =
        JdbcAdminUserRepository(dataSource)

    @Bean
    fun auditLogRepository(dataSource: DataSource): AuditLogRepository =
        JdbcAuditLogRepository(dataSource)

    @Bean
    fun eventInboxRepository(dataSource: DataSource): EventInboxRepository =
        JdbcEventInboxRepository(dataSource)

    @Bean
    fun botLeaseRepository(dataSource: DataSource): BotLeaseRepository =
        JdbcBotLeaseRepository(dataSource)

    @Bean
    fun outboxRepository(dataSource: DataSource): OutboxRepository =
        JdbcOutboxRepository(dataSource)

    @Bean
    fun pluginArtifactRepository(dataSource: DataSource): PluginArtifactRepository =
        JdbcPluginArtifactRepository(dataSource)

    @Bean
    fun botPluginBindingRepository(dataSource: DataSource): BotPluginBindingRepository =
        JdbcBotPluginBindingRepository(dataSource)

    @Bean
    fun pluginDeliveryRepository(dataSource: DataSource): PluginDeliveryRepository =
        JdbcPluginDeliveryRepository(dataSource)

    @Bean
    fun pluginStorageRepository(dataSource: DataSource): PluginStorageRepository =
        JdbcPluginStorageRepository(dataSource)

    @Bean
    fun appSecretKeyProvider(properties: AppSecretEncryptionProperties): KeyProvider {
        val configuredValue = normalized(properties.masterKey)
        val configuredFile = normalized(properties.masterKeyFile)
        if (configuredValue == null && configuredFile == null) {
            return StaticKeyProvider.unconfigured()
        }

        val encoded = configuredValue ?: readOrCreateKeyFile(checkNotNull(configuredFile))
        val keyBytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("AppSecret master key must be valid Base64", exception)
        }
        try {
            return StaticKeyProvider.configured(properties.keyId, keyBytes)
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
        }
    }

    @Bean
    fun appSecretCipher(appSecretKeyProvider: KeyProvider): AppSecretCipher =
        AesGcmAppSecretCipher(appSecretKeyProvider)

    @Bean
    fun botConfigurationService(
        botRepository: BotRepository,
        appSecretCipher: AppSecretCipher,
        changeListeners: List<BotConfigurationChangeListener>,
    ): BotConfigurationService = BotConfigurationService(
        botRepository,
        appSecretCipher,
        changeListener = BotConfigurationChangeListener.composite(changeListeners),
    )

    private fun readOrCreateKeyFile(file: String): String {
        val keyFile = Path.of(file)
        return try {
            readKeyFile(keyFile)
        } catch (_: NoSuchFileException) {
            createKeyFile(keyFile)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to read AppSecret master key file", exception)
        }
    }

    private fun createKeyFile(keyFile: Path): String {
        val keyBytes = ByteArray(MASTER_KEY_BYTES)
        var encodedBytes: ByteArray? = null
        try {
            keyFile.toAbsolutePath().normalize().parent?.let(Files::createDirectories)

            SECURE_RANDOM.nextBytes(keyBytes)
            val generatedKey = Base64.getEncoder().encode(keyBytes)
            encodedBytes = generatedKey
            try {
                openNewKeyFile(keyFile).use { channel ->
                    val encodedKey = ByteBuffer.wrap(generatedKey)
                    while (encodedKey.hasRemaining()) {
                        channel.write(encodedKey)
                    }
                    channel.force(true)
                }
            } catch (_: FileAlreadyExistsException) {
                return readAfterConcurrentCreate(keyFile)
            }
            return readKeyFile(keyFile)
        } catch (exception: IOException) {
            throw IllegalStateException("Unable to read AppSecret master key file", exception)
        } finally {
            Arrays.fill(keyBytes, 0.toByte())
            encodedBytes?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun openNewKeyFile(keyFile: Path): FileChannel {
        val options: Set<StandardOpenOption> = EnumSet.of(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        )
        return try {
            FileChannel.open(
                keyFile,
                options,
                PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            FileChannel.open(keyFile, options)
        }
    }

    private fun readAfterConcurrentCreate(keyFile: Path): String {
        var lastReadFailure: IOException? = null
        repeat(CONCURRENT_CREATE_READ_ATTEMPTS) {
            try {
                val encoded = readKeyFile(keyFile)
                if (isCompleteGeneratedKey(encoded)) {
                    return encoded
                }
            } catch (exception: NoSuchFileException) {
                lastReadFailure = exception
            }
            LockSupport.parkNanos(CONCURRENT_CREATE_READ_DELAY_NANOS)
        }
        val finalReadFailure = lastReadFailure
        if (finalReadFailure != null && Files.notExists(keyFile)) {
            throw finalReadFailure
        }
        return readKeyFile(keyFile)
    }

    private fun isCompleteGeneratedKey(encoded: String): Boolean {
        var decoded: ByteArray? = null
        return try {
            decoded = Base64.getDecoder().decode(encoded)
            decoded.size == MASTER_KEY_BYTES
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            decoded?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun readKeyFile(keyFile: Path): String =
        Files.readString(keyFile, StandardCharsets.US_ASCII).trim()

    private fun normalized(value: String?): String? = when {
        value.isNullOrBlank() -> null
        else -> value.trim()
    }

    private companion object {
        const val MASTER_KEY_BYTES = 32
        const val CONCURRENT_CREATE_READ_ATTEMPTS = 100
        const val CONCURRENT_CREATE_READ_DELAY_NANOS = 10_000_000L
        val SECURE_RANDOM = SecureRandom()
        val OWNER_READ_WRITE: Set<PosixFilePermission> =
            PosixFilePermissions.fromString("rw-------")
    }
}
