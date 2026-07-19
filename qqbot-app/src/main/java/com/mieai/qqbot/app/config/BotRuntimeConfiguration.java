package com.mieai.qqbot.app.config;

import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.JdbcBotRepository;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import com.mieai.qqbot.persistence.admin.JdbcAdminUserRepository;
import com.mieai.qqbot.persistence.audit.AuditLogRepository;
import com.mieai.qqbot.persistence.audit.JdbcAuditLogRepository;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.JdbcEventInboxRepository;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import com.mieai.qqbot.persistence.lease.JdbcBotLeaseRepository;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginStorageRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginStorageRepository;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.security.KeyProvider;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppSecretEncryptionProperties.class)
public class BotRuntimeConfiguration {
    private static final int MASTER_KEY_BYTES = 32;
    private static final int CONCURRENT_CREATE_READ_ATTEMPTS = 100;
    private static final long CONCURRENT_CREATE_READ_DELAY_NANOS = 10_000_000L;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            PosixFilePermissions.fromString("rw-------");

    @Bean
    BotRepository botRepository(DataSource dataSource) {
        return new JdbcBotRepository(dataSource);
    }

    @Bean
    AdminUserRepository adminUserRepository(DataSource dataSource) {
        return new JdbcAdminUserRepository(dataSource);
    }

    @Bean
    AuditLogRepository auditLogRepository(DataSource dataSource) {
        return new JdbcAuditLogRepository(dataSource);
    }

    @Bean
    EventInboxRepository eventInboxRepository(DataSource dataSource) {
        return new JdbcEventInboxRepository(dataSource);
    }

    @Bean
    BotLeaseRepository botLeaseRepository(DataSource dataSource) {
        return new JdbcBotLeaseRepository(dataSource);
    }

    @Bean
    OutboxRepository outboxRepository(DataSource dataSource) {
        return new JdbcOutboxRepository(dataSource);
    }

    @Bean
    PluginArtifactRepository pluginArtifactRepository(DataSource dataSource) {
        return new JdbcPluginArtifactRepository(dataSource);
    }

    @Bean
    BotPluginBindingRepository botPluginBindingRepository(DataSource dataSource) {
        return new JdbcBotPluginBindingRepository(dataSource);
    }

    @Bean
    PluginDeliveryRepository pluginDeliveryRepository(DataSource dataSource) {
        return new JdbcPluginDeliveryRepository(dataSource);
    }

    @Bean
    PluginStorageRepository pluginStorageRepository(DataSource dataSource) {
        return new JdbcPluginStorageRepository(dataSource);
    }

    @Bean
    KeyProvider appSecretKeyProvider(AppSecretEncryptionProperties properties) {
        String configuredValue = normalized(properties.getMasterKey());
        String configuredFile = normalized(properties.getMasterKeyFile());
        if (configuredValue == null && configuredFile == null) {
            return StaticKeyProvider.unconfigured();
        }

        String encoded = configuredValue != null ? configuredValue : readOrCreateKeyFile(configuredFile);
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AppSecret master key must be valid Base64", exception);
        }
        try {
            return StaticKeyProvider.configured(properties.getKeyId(), keyBytes);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    @Bean
    AppSecretCipher appSecretCipher(KeyProvider appSecretKeyProvider) {
        return new AesGcmAppSecretCipher(appSecretKeyProvider);
    }

    @Bean
    BotConfigurationService botConfigurationService(
            BotRepository botRepository,
            AppSecretCipher appSecretCipher,
            BotSupervisor botSupervisor) {
        return new BotConfigurationService(botRepository, appSecretCipher, botSupervisor);
    }

    private static String readOrCreateKeyFile(String file) {
        Path keyFile = Path.of(file);
        try {
            return readKeyFile(keyFile);
        } catch (NoSuchFileException exception) {
            return createKeyFile(keyFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read AppSecret master key file", exception);
        }
    }

    private static String createKeyFile(Path keyFile) {
        byte[] keyBytes = new byte[MASTER_KEY_BYTES];
        byte[] encodedBytes = null;
        try {
            Path parent = keyFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            SECURE_RANDOM.nextBytes(keyBytes);
            encodedBytes = Base64.getEncoder().encode(keyBytes);
            try (FileChannel channel = openNewKeyFile(keyFile)) {
                ByteBuffer encodedKey = ByteBuffer.wrap(encodedBytes);
                while (encodedKey.hasRemaining()) {
                    channel.write(encodedKey);
                }
                channel.force(true);
            } catch (FileAlreadyExistsException exception) {
                return readAfterConcurrentCreate(keyFile);
            }
            return readKeyFile(keyFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read AppSecret master key file", exception);
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
            if (encodedBytes != null) {
                Arrays.fill(encodedBytes, (byte) 0);
            }
        }
    }

    private static FileChannel openNewKeyFile(Path keyFile) throws IOException {
        Set<StandardOpenOption> options = EnumSet.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            return FileChannel.open(
                    keyFile,
                    options,
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        } catch (UnsupportedOperationException exception) {
            return FileChannel.open(keyFile, options);
        }
    }

    private static String readAfterConcurrentCreate(Path keyFile) throws IOException {
        IOException lastReadFailure = null;
        for (int attempt = 0; attempt < CONCURRENT_CREATE_READ_ATTEMPTS; attempt++) {
            try {
                String encoded = readKeyFile(keyFile);
                if (isCompleteGeneratedKey(encoded)) {
                    return encoded;
                }
            } catch (NoSuchFileException exception) {
                lastReadFailure = exception;
            }
            LockSupport.parkNanos(CONCURRENT_CREATE_READ_DELAY_NANOS);
        }
        if (lastReadFailure != null && Files.notExists(keyFile)) {
            throw lastReadFailure;
        }
        return readKeyFile(keyFile);
    }

    private static boolean isCompleteGeneratedKey(String encoded) {
        byte[] decoded = null;
        try {
            decoded = Base64.getDecoder().decode(encoded);
            return decoded.length == MASTER_KEY_BYTES;
        } catch (IllegalArgumentException exception) {
            return false;
        } finally {
            if (decoded != null) {
                Arrays.fill(decoded, (byte) 0);
            }
        }
    }

    private static String readKeyFile(Path keyFile) throws IOException {
        return Files.readString(keyFile, StandardCharsets.US_ASCII).strip();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
