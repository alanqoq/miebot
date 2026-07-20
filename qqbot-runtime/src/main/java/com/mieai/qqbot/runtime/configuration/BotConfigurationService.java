package com.mieai.qqbot.runtime.configuration;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.OptimisticLockException;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.security.AppSecret;
import com.mieai.qqbot.runtime.security.AppSecretBinding;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application service for persisted bot configuration and credential changes. */
public final class BotConfigurationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotConfigurationService.class);

    private final BotRepository repository;
    private final AppSecretCipher secretCipher;
    private final Clock clock;
    private final Supplier<UUID> uuidSupplier;
    private final BotConfigurationChangeListener changeListener;

    public BotConfigurationService(BotRepository repository, AppSecretCipher secretCipher) {
        this(
                repository,
                secretCipher,
                Clock.systemUTC(),
                UUID::randomUUID,
                BotConfigurationChangeListener.none());
    }

    public BotConfigurationService(
            BotRepository repository,
            AppSecretCipher secretCipher,
            BotConfigurationChangeListener changeListener) {
        this(repository, secretCipher, Clock.systemUTC(), UUID::randomUUID, changeListener);
    }

    public BotConfigurationService(
            BotRepository repository,
            AppSecretCipher secretCipher,
            Clock clock,
            Supplier<UUID> uuidSupplier) {
        this(
                repository,
                secretCipher,
                clock,
                uuidSupplier,
                BotConfigurationChangeListener.none());
    }

    public BotConfigurationService(
            BotRepository repository,
            AppSecretCipher secretCipher,
            Clock clock,
            Supplier<UUID> uuidSupplier,
            BotConfigurationChangeListener changeListener) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.secretCipher = Objects.requireNonNull(secretCipher, "secretCipher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier must not be null");
        this.changeListener =
                Objects.requireNonNull(changeListener, "changeListener must not be null");
    }

    public BotConfigurationView create(CreateBotCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        BotId id = BotId.of(Objects.requireNonNull(
                uuidSupplier.get(), "uuidSupplier must not return null"));
        Instant now = clock.instant();
        BotDefinition definition = new BotDefinition(
                id,
                command.displayName(),
                command.appId(),
                command.environment(),
                command.intents(),
                command.shardSpec(),
                command.enabled(),
                BotRevision.initial(),
                now,
                now,
                command.maxMediaUploadBytes());
        AppSecretBinding binding = binding(definition);
        SecretCiphertext encryptedSecret = secretCipher.encrypt(command.appSecret(), binding);
        repository.insert(new StoredBot(definition, encryptedSecret));
        BotConfigurationView created = toView(definition);
        notifyCommitted(created, BotConfigurationChangeKind.CREATED);
        return created;
    }

    public Optional<BotConfigurationView> findById(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return repository.findById(botId).map(StoredBot::definition).map(BotConfigurationService::toView);
    }

    public List<BotConfigurationView> findAll() {
        return repository.findAll().stream()
                .map(StoredBot::definition)
                .map(BotConfigurationService::toView)
                .toList();
    }

    public List<BotConfigurationView> findEnabled() {
        return repository.findEnabled().stream()
                .map(StoredBot::definition)
                .map(BotConfigurationService::toView)
                .toList();
    }

    public BotConfigurationView update(UpdateBotCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        StoredBot current = required(command.botId());
        requireRevision(current, command.expectedRevision());

        BotDefinition currentDefinition = current.definition();
        long maxMediaUploadBytes = command.maxMediaUploadBytes() == null
                ? currentDefinition.maxMediaUploadBytes() : command.maxMediaUploadBytes();
        BotDefinition desired = new BotDefinition(
                currentDefinition.id(),
                command.displayName(),
                command.appId(),
                command.environment(),
                command.intents(),
                command.shardSpec(),
                currentDefinition.enabled(),
                command.expectedRevision(),
                currentDefinition.createdAt(),
                updateTime(currentDefinition),
                maxMediaUploadBytes);
        SecretCiphertext encryptedSecret = selectSecret(current, desired, command.appSecret());
        BotConfigurationView updated =
                persistUpdate(desired, encryptedSecret, command.expectedRevision());
        notifyCommitted(updated, BotConfigurationChangeKind.UPDATED);
        return updated;
    }

    public BotConfigurationView enable(BotId botId, BotRevision expectedRevision) {
        return setEnabled(botId, expectedRevision, true);
    }

    public BotConfigurationView disable(BotId botId, BotRevision expectedRevision) {
        return setEnabled(botId, expectedRevision, false);
    }

    public void delete(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        StoredBot current = required(botId);
        if (!repository.delete(botId)) {
            throw new BotNotFoundException(botId);
        }
        try {
            changeListener.onCommitted(new BotConfigurationChange(
                    botId, current.definition().revision(), false,
                    BotConfigurationChangeKind.DELETED));
        } catch (RuntimeException exception) {
            LOGGER.warn("Bot {} was deleted but runtime notification failed ({})",
                    botId, exception.getClass().getSimpleName());
        }
    }

    private BotConfigurationView setEnabled(
            BotId botId, BotRevision expectedRevision, boolean enabled) {
        Objects.requireNonNull(botId, "botId must not be null");
        Objects.requireNonNull(expectedRevision, "expectedRevision must not be null");
        StoredBot current = required(botId);
        requireRevision(current, expectedRevision);
        BotDefinition existing = current.definition();
        BotDefinition desired = new BotDefinition(
                existing.id(),
                existing.displayName(),
                existing.appId(),
                existing.environment(),
                existing.intents(),
                existing.shardSpec(),
                enabled,
                expectedRevision,
                existing.createdAt(),
                updateTime(existing),
                existing.maxMediaUploadBytes());
        BotConfigurationView updated =
                persistUpdate(desired, current.appSecret(), expectedRevision);
        notifyCommitted(
                updated,
                enabled
                        ? BotConfigurationChangeKind.ENABLED
                        : BotConfigurationChangeKind.DISABLED);
        return updated;
    }

    private SecretCiphertext selectSecret(
            StoredBot current, BotDefinition desired, Optional<AppSecret> requestedSecret) {
        AppSecretBinding desiredBinding = binding(desired);
        if (requestedSecret.isPresent()) {
            return secretCipher.encrypt(requestedSecret.orElseThrow(), desiredBinding);
        }

        AppSecretBinding currentBinding = binding(current.definition());
        if (currentBinding.equals(desiredBinding)) {
            return current.appSecret();
        }

        try (AppSecret decrypted = secretCipher.decrypt(current.appSecret(), currentBinding)) {
            return secretCipher.encrypt(decrypted, desiredBinding);
        }
    }

    private BotConfigurationView persistUpdate(
            BotDefinition desired,
            SecretCiphertext encryptedSecret,
            BotRevision expectedRevision) {
        BotRevision updatedRevision = repository.update(
                new StoredBot(desired, encryptedSecret), expectedRevision);
        BotDefinition persisted = new BotDefinition(
                desired.id(),
                desired.displayName(),
                desired.appId(),
                desired.environment(),
                desired.intents(),
                desired.shardSpec(),
                desired.enabled(),
                updatedRevision,
                desired.createdAt(),
                desired.updatedAt(),
                desired.maxMediaUploadBytes());
        return toView(persisted);
    }

    private StoredBot required(BotId botId) {
        return repository.findById(botId).orElseThrow(() -> new BotNotFoundException(botId));
    }

    private static void requireRevision(StoredBot bot, BotRevision expectedRevision) {
        if (!bot.definition().revision().equals(expectedRevision)) {
            throw new OptimisticLockException(bot.id(), expectedRevision);
        }
    }

    private Instant updateTime(BotDefinition current) {
        Instant now = clock.instant();
        return now.isBefore(current.updatedAt()) ? current.updatedAt() : now;
    }

    private static AppSecretBinding binding(BotDefinition definition) {
        return new AppSecretBinding(
                definition.id(), definition.appId(), definition.environment());
    }

    private static BotConfigurationView toView(BotDefinition definition) {
        return new BotConfigurationView(
                definition.id(),
                definition.displayName(),
                definition.appId(),
                definition.environment(),
                definition.intents(),
                definition.shardSpec(),
                definition.enabled(),
                definition.revision(),
                definition.createdAt(),
                definition.updatedAt(),
                true,
                definition.maxMediaUploadBytes());
    }

    private void notifyCommitted(
            BotConfigurationView view, BotConfigurationChangeKind kind) {
        try {
            changeListener.onCommitted(new BotConfigurationChange(
                    view.id(), view.revision(), view.enabled(), kind));
        } catch (RuntimeException exception) {
            // Persistence already committed; runtime reconciliation has a periodic full-scan fallback.
            LOGGER.warn(
                    "Bot configuration notification failed for {} at revision {} ({})",
                    view.id(),
                    view.revision().value(),
                    exception.getClass().getSimpleName());
        }
    }
}
