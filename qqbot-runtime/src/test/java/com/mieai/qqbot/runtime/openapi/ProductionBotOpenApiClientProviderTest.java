package com.mieai.qqbot.runtime.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.client.QqClientOptions;
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
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.security.AppSecret;
import com.mieai.qqbot.runtime.security.AppSecretBinding;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ProductionBotOpenApiClientProviderTest {
    private static final BotId BOT_ID = BotId.parse("550e8400-e29b-41d4-a716-446655440000");
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void cachesByRevisionAndRebuildsAfterUpdateOrInvalidation() {
        AtomicReference<StoredBot> stored = new AtomicReference<>(bot(1L));
        AtomicInteger decryptions = new AtomicInteger();
        ProductionBotOpenApiClientProvider provider = new ProductionBotOpenApiClientProvider(
                repository(stored), cipher(decryptions), ignored -> QqClientOptions.builder().build());
        try {
            var first = provider.clientFor(BOT_ID);
            var cached = provider.clientFor(BOT_ID);
            stored.set(bot(2L));
            var revised = provider.clientFor(BOT_ID);
            provider.invalidate(BOT_ID);
            var invalidated = provider.clientFor(BOT_ID);

            assertThat(cached).isSameAs(first);
            assertThat(revised).isNotSameAs(first);
            assertThat(invalidated).isNotSameAs(revised);
            assertThat(decryptions).hasValue(3);
        } finally {
            provider.close();
        }
    }

    @Test
    void rejectsMissingBotsAndUseAfterClose() {
        AtomicReference<StoredBot> stored = new AtomicReference<>();
        ProductionBotOpenApiClientProvider provider = new ProductionBotOpenApiClientProvider(
                repository(stored), cipher(new AtomicInteger()),
                ignored -> QqClientOptions.builder().build());

        assertThatThrownBy(() -> provider.clientFor(BOT_ID))
                .isInstanceOf(BotNotFoundException.class);

        provider.close();
        assertThatIllegalStateException().isThrownBy(() -> provider.clientFor(BOT_ID))
                .withMessageContaining("closed");
    }

    private static StoredBot bot(long revision) {
        return new StoredBot(new BotDefinition(
                BOT_ID,
                "API Bot",
                QqAppId.of("app-1"),
                BotEnvironment.SANDBOX,
                GatewayIntents.GROUP_AND_C2C_EVENT,
                ShardSpec.single(),
                true,
                BotRevision.of(revision),
                NOW,
                NOW), SecretCiphertext.of("ciphertext", "key-1"));
    }

    private static BotRepository repository(AtomicReference<StoredBot> stored) {
        return new BotRepository() {
            @Override public Optional<StoredBot> findById(BotId id) {
                return Optional.ofNullable(stored.get()).filter(bot -> bot.id().equals(id));
            }
            @Override public List<StoredBot> findAll() { return List.of(); }
            @Override public List<StoredBot> findEnabled() { return List.of(); }
            @Override public void insert(StoredBot bot) { throw new UnsupportedOperationException(); }
            @Override public BotRevision update(StoredBot bot, BotRevision expected) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static AppSecretCipher cipher(AtomicInteger decryptions) {
        return new AppSecretCipher() {
            @Override public SecretCiphertext encrypt(
                    AppSecret secret, AppSecretBinding binding) {
                throw new UnsupportedOperationException();
            }

            @Override public AppSecret decrypt(
                    SecretCiphertext ciphertext, AppSecretBinding binding) {
                decryptions.incrementAndGet();
                return AppSecret.of("secret-value");
            }
        };
    }
}
