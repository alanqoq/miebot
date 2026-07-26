package com.mieai.qqbot.runtime.openapi

import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.security.AppSecret
import com.mieai.qqbot.runtime.security.AppSecretBinding
import com.mieai.qqbot.runtime.security.AppSecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProductionBotOpenApiClientProviderTest {
    @Test
    fun `caches by revision and rebuilds after update or invalidation`() {
        val stored = AtomicReference<StoredBot?>(bot(1L))
        val decryptions = AtomicInteger()
        val provider = ProductionBotOpenApiClientProvider(
            repository(stored), cipher(decryptions), { QqClientOptions() },
        )
        try {
            val first = provider.clientFor(BOT_ID)
            val cached = provider.clientFor(BOT_ID)
            stored.set(bot(2L))
            val revised = provider.clientFor(BOT_ID)
            provider.invalidate(BOT_ID)
            val invalidated = provider.clientFor(BOT_ID)

            assertThat(cached).isSameAs(first)
            assertThat(revised).isNotSameAs(first)
            assertThat(invalidated).isNotSameAs(revised)
            assertThat(decryptions).hasValue(3)
        } finally {
            provider.close()
        }
    }

    @Test
    fun `rejects missing bots and use after close`() {
        val stored = AtomicReference<StoredBot?>(null)
        val provider = ProductionBotOpenApiClientProvider(
            repository(stored), cipher(AtomicInteger()), { QqClientOptions() },
        )

        assertThatThrownBy { provider.clientFor(BOT_ID) }
            .isInstanceOf(BotNotFoundException::class.java)

        provider.close()
        assertThatIllegalStateException().isThrownBy { provider.clientFor(BOT_ID) }
            .withMessageContaining("closed")
    }

    private fun bot(revision: Long) = StoredBot(
        BotDefinition(
            BOT_ID, "API Bot", QqAppId.of("app-1"), BotEnvironment.SANDBOX,
            GatewayIntents.GROUP_AND_C2C_EVENT, ShardSpec.single(), true,
            BotRevision.of(revision), NOW, NOW,
        ),
        SecretCiphertext.of("ciphertext", "key-1"),
    )

    private fun repository(stored: AtomicReference<StoredBot?>) = object : BotRepository {
        override fun findById(id: BotId): StoredBot? = stored.get()?.takeIf { it.id == id }
        override fun findAll(): List<StoredBot> = emptyList()
        override fun findEnabled(): List<StoredBot> = emptyList()
        override fun insert(bot: StoredBot) = throw UnsupportedOperationException()
        override fun update(bot: StoredBot, expectedRevision: BotRevision): BotRevision =
            throw UnsupportedOperationException()
        override fun delete(id: BotId): Boolean = throw UnsupportedOperationException()
    }

    private fun cipher(decryptions: AtomicInteger) = object : AppSecretCipher {
        override fun encrypt(secret: AppSecret, binding: AppSecretBinding): SecretCiphertext = throw UnsupportedOperationException()
        override fun decrypt(ciphertext: SecretCiphertext, binding: AppSecretBinding): AppSecret {
            decryptions.incrementAndGet()
            return AppSecret.of("secret-value")
        }
    }

    private companion object {
        val BOT_ID: BotId = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
        val NOW: Instant = Instant.parse("2026-07-22T12:00:00Z")
    }
}
