package com.mieai.qqbot.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.configuration.BotConfigurationView;
import com.mieai.qqbot.runtime.configuration.CreateBotCommand;
import com.mieai.qqbot.runtime.security.AesGcmAppSecretCipher;
import com.mieai.qqbot.runtime.security.AppSecret;
import com.mieai.qqbot.runtime.security.AppSecretBinding;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.security.KeyProvider;
import com.mieai.qqbot.runtime.security.KeyUnavailableException;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BotRuntimeConfigurationTest {
    private final BotRuntimeConfiguration configuration = new BotRuntimeConfiguration();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void keepsApplicationAvailableWhenMasterKeyIsNotConfigured() {
        KeyProvider provider = configuration.appSecretKeyProvider(new AppSecretEncryptionProperties());

        assertThatThrownBy(provider::activeKey)
                .isInstanceOf(KeyUnavailableException.class)
                .hasMessageContaining("unavailable");
    }

    @Test
    void loadsAConfiguredBase64Aes256Key() {
        AppSecretEncryptionProperties properties = new AppSecretEncryptionProperties();
        properties.setKeyId("master-v1");
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));

        KeyProvider provider = configuration.appSecretKeyProvider(properties);

        assertThat(provider.activeKey().keyId()).isEqualTo("master-v1");
        assertThat(provider.toString()).contains("configured=true").contains("<redacted>");
    }

    @Test
    void readsAKeyFromASecretFile() throws Exception {
        Path keyFile = temporaryDirectory.resolve("app-secret.key");
        String existingKey = Base64.getEncoder().encodeToString(new byte[32]) + System.lineSeparator();
        Files.writeString(keyFile, existingKey);
        AppSecretEncryptionProperties properties = new AppSecretEncryptionProperties();
        properties.setMasterKeyFile(keyFile.toString());

        assertThat(configuration.appSecretKeyProvider(properties).activeKey().keyId())
                .isEqualTo("primary");
        assertThat(Files.readString(keyFile)).isEqualTo(existingKey);
    }

    @Test
    void createsAKeyAndMissingParentDirectoriesForFirstUse() throws Exception {
        Path keyFile = temporaryDirectory.resolve("nested/secrets/app-secret.key");
        AppSecretEncryptionProperties properties = new AppSecretEncryptionProperties();
        properties.setMasterKeyFile(keyFile.toString());

        KeyProvider provider = configuration.appSecretKeyProvider(properties);

        assertThat(provider.activeKey().keyId()).isEqualTo("primary");
        assertThat(Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII)))
                .hasSize(32);
        if (Files.getFileStore(keyFile).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(keyFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void concurrentFirstUseSharesTheSingleCreatedKey() throws Exception {
        Path keyFile = temporaryDirectory.resolve("concurrent/app-secret.key");
        AppSecretEncryptionProperties properties = new AppSecretEncryptionProperties();
        properties.setKeyId("concurrent-v1");
        properties.setMasterKeyFile(keyFile.toString());
        int workerCount = 12;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<KeyProvider>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < workerCount; worker++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return configuration.appSecretKeyProvider(properties);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<KeyProvider> providers = new ArrayList<>();
            for (Future<KeyProvider> future : futures) {
                providers.add(future.get(5, TimeUnit.SECONDS));
            }

            AppSecretBinding binding = new AppSecretBinding(
                    BotId.parse("550e8400-e29b-41d4-a716-446655440000"),
                    QqAppId.of("102012345"),
                    BotEnvironment.SANDBOX);
            SecretCiphertext encrypted;
            try (AppSecret secret = AppSecret.of("concurrent-secret")) {
                encrypted = new AesGcmAppSecretCipher(providers.getFirst()).encrypt(secret, binding);
            }
            for (KeyProvider provider : providers) {
                AesGcmAppSecretCipher cipher = new AesGcmAppSecretCipher(provider);
                assertThatCode(() -> {
                    try (AppSecret decrypted = cipher.decrypt(encrypted, binding)) {
                        assertThat(decrypted.isDestroyed()).isFalse();
                    }
                }).doesNotThrowAnyException();
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void explicitKeyTakesPriorityWithoutTouchingTheConfiguredFile() {
        Path keyFile = temporaryDirectory.resolve("must-not-be-created/app-secret.key");
        AppSecretEncryptionProperties properties = new AppSecretEncryptionProperties();
        properties.setMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setMasterKeyFile(keyFile.toString());

        assertThat(configuration.appSecretKeyProvider(properties).activeKey().keyId())
                .isEqualTo("primary");
        assertThat(keyFile).doesNotExist();
    }

    @Test
    void rejectsInvalidKeyConfiguration() {
        AppSecretEncryptionProperties invalidBase64 = new AppSecretEncryptionProperties();
        invalidBase64.setMasterKey("not base64");
        assertThatIllegalStateException()
                .isThrownBy(() -> configuration.appSecretKeyProvider(invalidBase64))
                .withMessageContaining("Base64");

        AppSecretEncryptionProperties shortKey = new AppSecretEncryptionProperties();
        shortKey.setMasterKey(Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> configuration.appSecretKeyProvider(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
    }

    @Test
    void configurationServicePublishesCreateEnableAndDisableToSupervisor() {
        AtomicReference<StoredBot> stored = new AtomicReference<>();
        BotRepository repository = mock(BotRepository.class);
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return null;
        }).when(repository).insert(any(StoredBot.class));
        when(repository.findById(any(BotId.class)))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.update(any(StoredBot.class), any(BotRevision.class)))
                .thenAnswer(invocation -> {
                    StoredBot desired = invocation.getArgument(0);
                    BotRevision next = ((BotRevision) invocation.getArgument(1)).next();
                    BotDefinition definition = desired.definition();
                    stored.set(new StoredBot(
                            new BotDefinition(
                                    definition.id(),
                                    definition.displayName(),
                                    definition.appId(),
                                    definition.environment(),
                                    definition.intents(),
                                    definition.shardSpec(),
                                    definition.enabled(),
                                    next,
                                    definition.createdAt(),
                                    definition.updatedAt()),
                            desired.appSecret()));
                    return next;
                });
        AppSecretCipher cipher = new AesGcmAppSecretCipher(
                StaticKeyProvider.configured("test-key", new byte[32]));
        BotSupervisor supervisor = mock(BotSupervisor.class);
        BotConfigurationService service =
                configuration.botConfigurationService(repository, cipher, supervisor);

        BotConfigurationView created;
        try (AppSecret secret = AppSecret.of("runtime-listener-secret")) {
            created = service.create(new CreateBotCommand(
                    "Runtime Listener Bot",
                    QqAppId.of("1905208810"),
                    BotEnvironment.PRODUCTION,
                    GatewayIntents.of(1L << 25),
                    ShardSpec.single(),
                    false,
                    secret));
        }
        var enabled = service.enable(created.id(), created.revision());
        service.disable(enabled.id(), enabled.revision());

        ArgumentCaptor<BotConfigurationChange> changes =
                ArgumentCaptor.forClass(BotConfigurationChange.class);
        verify(supervisor, times(3)).onCommitted(changes.capture());
        assertThat(changes.getAllValues())
                .extracting(BotConfigurationChange::kind)
                .containsExactly(
                        BotConfigurationChangeKind.CREATED,
                        BotConfigurationChangeKind.ENABLED,
                        BotConfigurationChangeKind.DISABLED);
        assertThat(changes.getAllValues())
                .extracting(BotConfigurationChange::enabled)
                .containsExactly(false, true, false);
    }
}
