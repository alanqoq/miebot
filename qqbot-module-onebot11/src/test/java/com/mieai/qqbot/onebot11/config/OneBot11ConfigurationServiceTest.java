package com.mieai.qqbot.onebot11.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.StaticKeyProvider;
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class OneBot11ConfigurationServiceTest {
    @TempDir
    private Path directory;

    @Test
    void encryptsTokenPersistsDefaultsAndUsesOptimisticRevision() {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("settings.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        BotRepository bots = mock(BotRepository.class);
        when(bots.findById(botId)).thenReturn(Optional.of(mock(StoredBot.class)));
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        var cipher = new AesGcmConfigurationSecretCipher(
                StaticKeyProvider.configured("test", key));
        var service = new OneBot11ConfigurationService(
                new OneBot11ConfigRepository(dataSource), bots, cipher,
                Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC));

        OneBot11SettingsView saved = service.update(botId, new OneBot11SettingsRequest(
                0L, true, true, "127.0.0.1", 5700,
                false, null, "onebot-secret", false,
                true, 15_000, 3_000));

        assertThat(saved.revision()).isEqualTo(1L);
        assertThat(saved.accessTokenConfigured()).isTrue();
        assertThat(service.resolve(botId)).get()
                .extracting(ResolvedOneBot11Config::accessToken)
                .isEqualTo("onebot-secret");
        String ciphertext = new JdbcTemplate(dataSource).queryForObject(
                "SELECT access_token_ciphertext FROM onebot11_configs WHERE bot_id=?",
                String.class, botId.toString());
        assertThat(ciphertext).doesNotContain("onebot-secret");

        assertThatThrownBy(() -> service.update(botId, new OneBot11SettingsRequest(
                0L, false, false, "127.0.0.1", null,
                false, null, null, false, true, 15_000, 3_000)))
                .isInstanceOf(OneBot11RevisionConflictException.class);
    }

    @Test
    void rejectsEnabledTransportsWithoutToken() {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("missing-token.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        BotRepository bots = mock(BotRepository.class);
        when(bots.findById(botId)).thenReturn(Optional.of(mock(StoredBot.class)));
        byte[] key = new byte[32];
        var service = new OneBot11ConfigurationService(
                new OneBot11ConfigRepository(dataSource), bots,
                new AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test", key)));

        assertThatThrownBy(() -> service.update(botId, new OneBot11SettingsRequest(
                0L, true, true, "127.0.0.1", 5700,
                false, null, null, false, true, 15_000, 3_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("access token");
    }
}
