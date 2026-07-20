package com.mieai.qqbot.runtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.OptimisticLockException;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.configuration.BotConfigurationView;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange;
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind;
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.configuration.CreateBotCommand;
import com.mieai.qqbot.runtime.configuration.UpdateBotCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotConfigurationServiceTest {
    private static final String ORIGINAL_SECRET = "original-client-secret";
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    private InMemoryBotRepository repository;
    private AesGcmAppSecretCipher cipher;
    private BotConfigurationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryBotRepository();
        cipher = new AesGcmAppSecretCipher(
                StaticKeyProvider.configured("master-key-v1", keyBytes((byte) 0x31)));
        AtomicLong ids = new AtomicLong(1L);
        service = new BotConfigurationService(
                repository,
                cipher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new UUID(0x550e8400e29b41d4L, ids.getAndIncrement()));
    }

    @Test
    void createsAndQueriesOnlyASecretConfiguredFlag() {
        BotConfigurationView created = create("102012345", true, ORIGINAL_SECRET);

        assertThat(created.id()).isNotNull();
        assertThat(created.revision()).isEqualTo(BotRevision.initial());
        assertThat(created.enabled()).isTrue();
        assertThat(created.secretConfigured()).isTrue();
        assertThat(created.toString()).doesNotContain(ORIGINAL_SECRET);
        assertThat(service.findById(created.id())).contains(created);
        assertThat(service.findAll()).containsExactly(created);
        assertThat(service.findEnabled()).containsExactly(created);
        assertThat(Arrays.stream(BotConfigurationView.class.getRecordComponents())
                        .map(component -> component.getType()))
                .allMatch(type -> type != AppSecret.class && type != SecretCiphertext.class);

        StoredBot stored = repository.required(created.id());
        assertThat(stored.appSecret().ciphertext()).startsWith("v1.").doesNotContain(ORIGINAL_SECRET);
        assertThat(stored.appSecret().toString()).doesNotContain(ORIGINAL_SECRET);
        try (AppSecret decrypted = cipher.decrypt(stored.appSecret(), binding(stored.definition()))) {
            assertThat(revealForTest(decrypted)).isEqualTo(ORIGINAL_SECRET);
        }
    }

    @Test
    void omittedSecretIsRetainedWhenItsAadBindingDoesNotChange() {
        BotConfigurationView created = create("102012345", false, ORIGINAL_SECRET);
        SecretCiphertext before = repository.required(created.id()).appSecret();
        assertThat(service.findAll()).containsExactly(created);
        assertThat(service.findEnabled()).isEmpty();

        BotConfigurationView updated = service.update(new UpdateBotCommand(
                created.id(),
                created.revision(),
                "Renamed Bot",
                created.appId(),
                created.environment(),
                GatewayIntents.of(1L << 26),
                new ShardSpec(1, 2),
                Optional.empty()));

        assertThat(updated.revision()).isEqualTo(BotRevision.of(2));
        assertThat(updated.displayName()).isEqualTo("Renamed Bot");
        assertThat(updated.enabled()).isFalse();
        assertThat(repository.required(created.id()).appSecret()).isEqualTo(before);
    }

    @Test
    void omittedMediaLimitRetainsTheCurrentBotSpecificValue() {
        BotConfigurationView created;
        try (AppSecret secret = AppSecret.of(ORIGINAL_SECRET)) {
            created = service.create(new CreateBotCommand(
                    "Example Bot", QqAppId.of("102012345"), BotEnvironment.SANDBOX,
                    GatewayIntents.of(512L), ShardSpec.single(), false, secret,
                    32L * 1024L * 1024L));
        }

        BotConfigurationView updated = service.update(new UpdateBotCommand(
                created.id(), created.revision(), "Renamed Bot", created.appId(),
                created.environment(), created.intents(), created.shardSpec(), Optional.empty()));

        assertThat(updated.maxMediaUploadBytes()).isEqualTo(32L * 1024L * 1024L);
        assertThat(repository.required(created.id()).definition().maxMediaUploadBytes())
                .isEqualTo(32L * 1024L * 1024L);
    }

    @Test
    void omittedSecretIsReencryptedWhenAadBoundConfigurationChanges() {
        BotConfigurationView created = create("102012345", false, ORIGINAL_SECRET);
        StoredBot before = repository.required(created.id());

        BotConfigurationView updated = service.update(new UpdateBotCommand(
                created.id(),
                created.revision(),
                created.displayName(),
                QqAppId.of("102099999"),
                BotEnvironment.PRODUCTION,
                created.intents(),
                created.shardSpec(),
                Optional.empty()));

        StoredBot after = repository.required(created.id());
        assertThat(updated.revision()).isEqualTo(BotRevision.of(2));
        assertThat(after.appSecret()).isNotEqualTo(before.appSecret());
        assertThatThrownBy(() -> cipher.decrypt(after.appSecret(), binding(before.definition())))
                .isInstanceOf(SecretDecryptionException.class);
        try (AppSecret decrypted = cipher.decrypt(after.appSecret(), binding(after.definition()))) {
            assertThat(revealForTest(decrypted)).isEqualTo(ORIGINAL_SECRET);
        }
    }

    @Test
    void replacesSecretWithoutExposingItThroughCommandOrView() {
        BotConfigurationView created = create("102012345", false, ORIGINAL_SECRET);
        String rotatedValue = "rotated-client-secret";

        try (AppSecret rotated = AppSecret.of(rotatedValue)) {
            UpdateBotCommand command = new UpdateBotCommand(
                    created.id(),
                    created.revision(),
                    created.displayName(),
                    created.appId(),
                    created.environment(),
                    created.intents(),
                    created.shardSpec(),
                    Optional.of(rotated));
            assertThat(command.toString()).contains("<redacted>").doesNotContain(rotatedValue);
            BotConfigurationView updated = service.update(command);
            assertThat(updated.toString()).doesNotContain(rotatedValue);
        }

        StoredBot stored = repository.required(created.id());
        try (AppSecret decrypted = cipher.decrypt(stored.appSecret(), binding(stored.definition()))) {
            assertThat(revealForTest(decrypted)).isEqualTo(rotatedValue);
        }
    }

    @Test
    void enableAndDisableUseRevisionOptimisticLocking() {
        BotConfigurationView created = create("102012345", false, ORIGINAL_SECRET);

        BotConfigurationView enabled = service.enable(created.id(), BotRevision.initial());

        assertThat(enabled.enabled()).isTrue();
        assertThat(enabled.revision()).isEqualTo(BotRevision.of(2));
        assertThatThrownBy(() -> service.disable(created.id(), BotRevision.initial()))
                .isInstanceOfSatisfying(OptimisticLockException.class, exception -> {
                    assertThat(exception.botId()).isEqualTo(created.id());
                    assertThat(exception.expectedRevision()).isEqualTo(BotRevision.initial());
                });
        assertThat(repository.required(created.id()).definition().enabled()).isTrue();
        assertThat(repository.required(created.id()).definition().revision()).isEqualTo(BotRevision.of(2));

        BotConfigurationView disabled = service.disable(created.id(), BotRevision.of(2));
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.revision()).isEqualTo(BotRevision.of(3));
    }

    @Test
    void notifiesOnlyAfterCommittedWritesAndContainsNoSecret() {
        List<BotConfigurationChange> changes = new ArrayList<>();
        BotConfigurationService notifying = new BotConfigurationService(
                repository,
                cipher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("550e8400-e29b-41d4-a716-446655440055"),
                changes::add);

        BotConfigurationView created;
        try (AppSecret secret = AppSecret.of(ORIGINAL_SECRET)) {
            created = notifying.create(command("102012345", false, secret));
        }
        BotConfigurationView updated = notifying.update(new UpdateBotCommand(
                created.id(),
                created.revision(),
                "Renamed Bot",
                created.appId(),
                created.environment(),
                created.intents(),
                created.shardSpec(),
                Optional.empty()));
        assertThatThrownBy(() -> notifying.enable(created.id(), created.revision()))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(changes).hasSize(2);
        BotConfigurationView enabled = notifying.enable(created.id(), updated.revision());
        BotConfigurationView disabled = notifying.disable(created.id(), enabled.revision());

        assertThat(changes)
                .extracting(BotConfigurationChange::kind)
                .containsExactly(
                        BotConfigurationChangeKind.CREATED,
                        BotConfigurationChangeKind.UPDATED,
                        BotConfigurationChangeKind.ENABLED,
                        BotConfigurationChangeKind.DISABLED);
        assertThat(changes)
                .extracting(BotConfigurationChange::revision)
                .containsExactly(
                        BotRevision.initial(),
                        BotRevision.of(2),
                        BotRevision.of(3),
                        BotRevision.of(4));
        assertThat(changes.toString()).doesNotContain(ORIGINAL_SECRET);
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void listenerFailureDoesNotFailAnAlreadyCommittedWrite() {
        BotConfigurationService notifying = new BotConfigurationService(
                repository,
                cipher,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("550e8400-e29b-41d4-a716-446655440066"),
                ignored -> {
                    throw new IllegalStateException("listener unavailable");
                });

        BotConfigurationView created;
        try (AppSecret secret = AppSecret.of(ORIGINAL_SECRET)) {
            created = notifying.create(command("102012345", true, secret));
        }

        assertThat(repository.findById(created.id())).isPresent();
    }

    @Test
    void missingMasterKeyAllowsQueriesButRejectsCreationAndRotation() {
        BotConfigurationView created = create("102012345", false, ORIGINAL_SECRET);
        BotConfigurationService unavailable = new BotConfigurationService(
                repository,
                new AesGcmAppSecretCipher(StaticKeyProvider.unconfigured()),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> UUID.fromString("550e8400-e29b-41d4-a716-446655440099"));

        assertThatNoException().isThrownBy(() -> unavailable.findById(created.id()));
        BotConfigurationView metadataOnlyUpdate = unavailable.update(new UpdateBotCommand(
                created.id(),
                created.revision(),
                "Metadata Only",
                created.appId(),
                created.environment(),
                created.intents(),
                created.shardSpec(),
                Optional.empty()));
        assertThat(metadataOnlyUpdate.revision()).isEqualTo(BotRevision.of(2));

        try (AppSecret rotated = AppSecret.of("cannot-rotate-without-key")) {
            UpdateBotCommand rotation = new UpdateBotCommand(
                    created.id(),
                    BotRevision.of(2),
                    metadataOnlyUpdate.displayName(),
                    metadataOnlyUpdate.appId(),
                    metadataOnlyUpdate.environment(),
                    metadataOnlyUpdate.intents(),
                    metadataOnlyUpdate.shardSpec(),
                    Optional.of(rotated));
            assertThatThrownBy(() -> unavailable.update(rotation))
                    .isInstanceOf(KeyUnavailableException.class);
        }
        assertThat(repository.required(created.id()).definition().revision()).isEqualTo(BotRevision.of(2));

        try (AppSecret secret = AppSecret.of("new-secret")) {
            CreateBotCommand create = command("102099999", false, secret);
            assertThatThrownBy(() -> unavailable.create(create))
                    .isInstanceOf(KeyUnavailableException.class);
        }
    }

    @Test
    void validatesInputsAndReportsMissingBots() {
        try (AppSecret secret = AppSecret.of("valid-secret")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> command("102012345", false, secret, " blank "));
        }
        BotId missing = BotId.parse("550e8400-e29b-41d4-a716-446655440099");
        assertThat(service.findById(missing)).isEmpty();
        assertThatThrownBy(() -> service.enable(missing, BotRevision.initial()))
                .isInstanceOfSatisfying(
                        BotNotFoundException.class,
                        exception -> assertThat(exception.botId()).isEqualTo(missing));
    }

    private BotConfigurationView create(String appId, boolean enabled, String secretValue) {
        try (AppSecret secret = AppSecret.of(secretValue)) {
            CreateBotCommand command = command(appId, enabled, secret);
            assertThat(command.toString()).contains("<redacted>").doesNotContain(secretValue);
            return service.create(command);
        }
    }

    private static CreateBotCommand command(String appId, boolean enabled, AppSecret secret) {
        return command(appId, enabled, secret, "Example Bot");
    }

    private static CreateBotCommand command(
            String appId, boolean enabled, AppSecret secret, String displayName) {
        return new CreateBotCommand(
                displayName,
                QqAppId.of(appId),
                BotEnvironment.SANDBOX,
                GatewayIntents.of(512L),
                ShardSpec.single(),
                enabled,
                secret);
    }

    private static AppSecretBinding binding(BotDefinition definition) {
        return new AppSecretBinding(definition.id(), definition.appId(), definition.environment());
    }

    private static String revealForTest(AppSecret secret) {
        char[] characters = secret.copyValue();
        try {
            return new String(characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }

    private static byte[] keyBytes(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class InMemoryBotRepository implements BotRepository {
        private final Map<BotId, StoredBot> bots = new LinkedHashMap<>();

        @Override
        public Optional<StoredBot> findById(BotId id) {
            return Optional.ofNullable(bots.get(id));
        }

        @Override
        public List<StoredBot> findAll() {
            return List.copyOf(bots.values());
        }

        @Override
        public List<StoredBot> findEnabled() {
            return bots.values().stream()
                    .filter(bot -> bot.definition().enabled())
                    .toList();
        }

        @Override
        public void insert(StoredBot bot) {
            if (bots.putIfAbsent(bot.id(), bot) != null) {
                throw new IllegalStateException("duplicate bot");
            }
        }

        @Override
        public BotRevision update(StoredBot bot, BotRevision expectedRevision) {
            StoredBot current = bots.get(bot.id());
            if (current == null || !current.definition().revision().equals(expectedRevision)) {
                throw new OptimisticLockException(bot.id(), expectedRevision);
            }
            if (!bot.definition().revision().equals(expectedRevision)) {
                throw new IllegalArgumentException("bot revision must equal expectedRevision");
            }

            BotRevision next = expectedRevision.next();
            BotDefinition desired = bot.definition();
            BotDefinition persisted = new BotDefinition(
                    desired.id(),
                    desired.displayName(),
                    desired.appId(),
                    desired.environment(),
                    desired.intents(),
                    desired.shardSpec(),
                    desired.enabled(),
                    next,
                    desired.createdAt(),
                    desired.updatedAt(),
                    desired.maxMediaUploadBytes());
            bots.put(bot.id(), new StoredBot(persisted, bot.appSecret()));
            return next;
        }

        StoredBot required(BotId id) {
            return findById(id).orElseThrow();
        }
    }
}
