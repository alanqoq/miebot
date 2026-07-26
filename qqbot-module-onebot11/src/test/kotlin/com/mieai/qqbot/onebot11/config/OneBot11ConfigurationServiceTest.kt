package com.mieai.qqbot.onebot11.config

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher
import com.mieai.qqbot.runtime.security.StaticKeyProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Arrays
import java.util.UUID

class OneBot11ConfigurationServiceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `encrypts token persists defaults and uses optimistic revision`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("settings.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val bots = mock(BotRepository::class.java)
        `when`(bots.findById(botId)).thenReturn(mock(StoredBot::class.java))
        val key = ByteArray(32)
        Arrays.fill(key, 7.toByte())
        val cipher = AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test", key))
        val service = OneBot11ConfigurationService(
            OneBot11ConfigRepository(dataSource),
            bots,
            cipher,
            Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC),
        )

        val saved = service.update(
            botId,
            OneBot11SettingsRequest(
                0L,
                true,
                true,
                "127.0.0.1",
                5700,
                false,
                null,
                "onebot-secret",
                false,
                true,
                15_000,
                3_000,
            ),
        )

        assertThat(saved.revision).isEqualTo(1L)
        assertThat(saved.accessTokenConfigured).isTrue()
        assertThat(requireNotNull(service.resolve(botId)))
            .extracting(ResolvedOneBot11Config::accessToken)
            .isEqualTo("onebot-secret")
        val ciphertext = JdbcTemplate(dataSource).queryForObject(
            "SELECT access_token_ciphertext FROM onebot11_configs WHERE bot_id=?",
            String::class.java,
            botId.toString(),
        )
        assertThat(ciphertext).doesNotContain("onebot-secret")

        assertThatThrownBy {
            service.update(
                botId,
                OneBot11SettingsRequest(
                    0L,
                    false,
                    false,
                    "127.0.0.1",
                    null,
                    false,
                    null,
                    null,
                    false,
                    true,
                    15_000,
                    3_000,
                ),
            )
        }.isInstanceOf(OneBot11RevisionConflictException::class.java)
    }

    @Test
    fun `rejects enabled transports without token`() {
        val botId = BotId.of(UUID.randomUUID())
        val dataSource = OneBotTestDatabase.create(directory.resolve("missing-token.db"))
        OneBotTestDatabase.insertBot(dataSource, botId.toString())
        val bots = mock(BotRepository::class.java)
        `when`(bots.findById(botId)).thenReturn(mock(StoredBot::class.java))
        val service = OneBot11ConfigurationService(
            OneBot11ConfigRepository(dataSource),
            bots,
            AesGcmConfigurationSecretCipher(StaticKeyProvider.configured("test", ByteArray(32))),
        )

        assertThatThrownBy {
            service.update(
                botId,
                OneBot11SettingsRequest(
                    0L,
                    true,
                    true,
                    "127.0.0.1",
                    5700,
                    false,
                    null,
                    null,
                    false,
                    true,
                    15_000,
                    3_000,
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("access token")
    }
}
