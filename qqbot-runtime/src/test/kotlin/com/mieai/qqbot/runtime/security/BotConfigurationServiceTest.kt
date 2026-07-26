package com.mieai.qqbot.runtime.security

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.OptimisticLockException
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.configuration.BotConfigurationChange
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeKind
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import com.mieai.qqbot.runtime.configuration.BotConfigurationService
import com.mieai.qqbot.runtime.configuration.BotConfigurationView
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.configuration.CreateBotCommand
import com.mieai.qqbot.runtime.configuration.UpdateBotCommand
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BotConfigurationServiceTest {
    private lateinit var repository: InMemoryBotRepository
    private lateinit var cipher: AesGcmAppSecretCipher
    private lateinit var service: BotConfigurationService

    @BeforeEach
    fun setUp() {
        repository = InMemoryBotRepository()
        cipher = AesGcmAppSecretCipher(StaticKeyProvider.configured("master-key-v1", keyBytes(0x31)))
        val ids = AtomicLong(1L)
        service = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID(0x550e8400e29b41d4L, ids.getAndIncrement()) },
        )
    }

    @Test
    fun `creates and queries only a secret configured flag`() {
        val created = create("102012345", true, ORIGINAL_SECRET)

        assertThat(created.id).isNotNull()
        assertThat(created.revision).isEqualTo(BotRevision.initial())
        assertThat(created.enabled).isTrue()
        assertThat(created.secretConfigured).isTrue()
        assertThat(created.toString()).doesNotContain(ORIGINAL_SECRET)
        assertThat(service.findById(created.id)).isEqualTo(created)
        assertThat(service.findAll()).containsExactly(created)
        assertThat(service.findEnabled()).containsExactly(created)
        assertThat(Arrays.stream(BotConfigurationView::class.java.declaredFields).map { it.type })
            .allMatch { it != AppSecret::class.java && it != SecretCiphertext::class.java }

        val stored = repository.required(created.id)
        assertThat(stored.appSecret.ciphertext).startsWith("v1.").doesNotContain(ORIGINAL_SECRET)
        assertThat(stored.appSecret.toString()).doesNotContain(ORIGINAL_SECRET)
        cipher.decrypt(stored.appSecret, binding(stored.definition)).use {
            assertThat(revealForTest(it)).isEqualTo(ORIGINAL_SECRET)
        }
    }

    @Test
    fun `omitted secret is retained when its aad binding does not change`() {
        val created = create("102012345", false, ORIGINAL_SECRET)
        val before = repository.required(created.id).appSecret
        assertThat(service.findAll()).containsExactly(created)
        assertThat(service.findEnabled()).isEmpty()

        val updated = service.update(
            UpdateBotCommand(
                created.id,
                created.revision,
                "Renamed Bot",
                created.appId,
                created.environment,
                GatewayIntents.of(1L shl 26),
                ShardSpec(1, 2),
                null,
            ),
        )

        assertThat(updated.revision).isEqualTo(BotRevision.of(2))
        assertThat(updated.displayName).isEqualTo("Renamed Bot")
        assertThat(updated.enabled).isFalse()
        assertThat(repository.required(created.id).appSecret).isEqualTo(before)
    }

    @Test
    fun `omitted media limit retains the current bot specific value`() {
        val created = AppSecret.of(ORIGINAL_SECRET).use { secret ->
            service.create(
                CreateBotCommand(
                    "Example Bot", QqAppId.of("102012345"), BotEnvironment.SANDBOX,
                    GatewayIntents.of(512L), ShardSpec.single(), false, secret,
                    32L * 1024L * 1024L,
                ),
            )
        }

        val updated = service.update(
            UpdateBotCommand(
                created.id, created.revision, "Renamed Bot", created.appId,
                created.environment, created.intents, created.shardSpec, null,
            ),
        )

        assertThat(updated.maxMediaUploadBytes).isEqualTo(32L * 1024L * 1024L)
        assertThat(repository.required(created.id).definition.maxMediaUploadBytes)
            .isEqualTo(32L * 1024L * 1024L)
    }

    @Test
    fun `omitted secret is reencrypted when aad bound configuration changes`() {
        val created = create("102012345", false, ORIGINAL_SECRET)
        val before = repository.required(created.id)

        val updated = service.update(
            UpdateBotCommand(
                created.id, created.revision, created.displayName, QqAppId.of("102099999"),
                BotEnvironment.PRODUCTION, created.intents, created.shardSpec, null,
            ),
        )

        val after = repository.required(created.id)
        assertThat(updated.revision).isEqualTo(BotRevision.of(2))
        assertThat(after.appSecret).isNotEqualTo(before.appSecret)
        assertThatThrownBy { cipher.decrypt(after.appSecret, binding(before.definition)) }
            .isInstanceOf(SecretDecryptionException::class.java)
        cipher.decrypt(after.appSecret, binding(after.definition)).use {
            assertThat(revealForTest(it)).isEqualTo(ORIGINAL_SECRET)
        }
    }

    @Test
    fun `replaces secret without exposing it through command or view`() {
        val created = create("102012345", false, ORIGINAL_SECRET)
        val rotatedValue = "rotated-client-secret"

        AppSecret.of(rotatedValue).use { rotated ->
            val command = UpdateBotCommand(
                created.id, created.revision, created.displayName, created.appId,
                created.environment, created.intents, created.shardSpec, rotated,
            )
            assertThat(command.toString()).contains("<redacted>").doesNotContain(rotatedValue)
            val updated = service.update(command)
            assertThat(updated.toString()).doesNotContain(rotatedValue)
        }

        val stored = repository.required(created.id)
        cipher.decrypt(stored.appSecret, binding(stored.definition)).use {
            assertThat(revealForTest(it)).isEqualTo(rotatedValue)
        }
    }

    @Test
    fun `enable and disable use revision optimistic locking`() {
        val created = create("102012345", false, ORIGINAL_SECRET)

        val enabled = service.enable(created.id, BotRevision.initial())

        assertThat(enabled.enabled).isTrue()
        assertThat(enabled.revision).isEqualTo(BotRevision.of(2))
        assertThatThrownBy { service.disable(created.id, BotRevision.initial()) }
            .isInstanceOfSatisfying(OptimisticLockException::class.java) { exception ->
                assertThat(exception.botId).isEqualTo(created.id)
                assertThat(exception.expectedRevision).isEqualTo(BotRevision.initial())
            }
        assertThat(repository.required(created.id).definition.enabled).isTrue()
        assertThat(repository.required(created.id).definition.revision).isEqualTo(BotRevision.of(2))

        val disabled = service.disable(created.id, BotRevision.of(2))
        assertThat(disabled.enabled).isFalse()
        assertThat(disabled.revision).isEqualTo(BotRevision.of(3))
    }

    @Test
    fun `notifies only after committed writes and contains no secret`() {
        val changes = ArrayList<BotConfigurationChange>()
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.fromString("550e8400-e29b-41d4-a716-446655440055") },
            changeListener = BotConfigurationChangeListener { changes.add(it) },
        )

        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", false, it)) }
        val updated = notifying.update(
            UpdateBotCommand(
                created.id, created.revision, "Renamed Bot", created.appId,
                created.environment, created.intents, created.shardSpec, null,
            ),
        )
        assertThatThrownBy { notifying.enable(created.id, created.revision) }
            .isInstanceOf(OptimisticLockException::class.java)
        assertThat(changes).hasSize(2)
        val enabled = notifying.enable(created.id, updated.revision)
        val disabled = notifying.disable(created.id, enabled.revision)

        assertThat(changes.map { it.kind }).containsExactly(
            BotConfigurationChangeKind.CREATED,
            BotConfigurationChangeKind.UPDATED,
            BotConfigurationChangeKind.ENABLED,
            BotConfigurationChangeKind.DISABLED,
        )
        assertThat(changes.map { it.revision }).containsExactly(
            BotRevision.initial(), BotRevision.of(2), BotRevision.of(3), BotRevision.of(4),
        )
        assertThat(changes.toString()).doesNotContain(ORIGINAL_SECRET)
        assertThat(disabled.enabled).isFalse()
    }

    @Test
    fun `listener failure does not fail an already committed write`() {
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.fromString("550e8400-e29b-41d4-a716-446655440066") },
            changeListener = BotConfigurationChangeListener {
                throw IllegalStateException("listener unavailable")
            },
        )

        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", true, it)) }

        assertThat(repository.findById(created.id)).isNotNull()
    }

    @Test
    fun `prepares runtime before deleting and notifies only after the delete commits`() {
        val phases = ArrayList<String>()
        val prepared = AtomicBoolean()
        val listener = object : BotConfigurationChangeListener {
            override fun beforeDelete(botId: BotId) {
                assertThat(repository.findById(botId)).isNotNull()
                prepared.set(true)
                phases += "prepared"
            }

            override fun onCommitted(change: BotConfigurationChange) {
                if (change.kind != BotConfigurationChangeKind.DELETED) return
                assertThat(prepared).isTrue()
                assertThat(repository.findById(change.botId)).isNull()
                phases += "committed"
            }
        }
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.randomUUID() },
            changeListener = listener,
        )
        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", true, it)) }

        notifying.delete(created.id)

        assertThat(repository.findById(created.id)).isNull()
        assertThat(phases).containsExactly("prepared", "committed")
    }

    @Test
    fun `failed deletion preparation keeps the bot and invokes abort cleanup`() {
        val aborted = AtomicBoolean()
        val listener = object : BotConfigurationChangeListener {
            override fun beforeDelete(botId: BotId) {
                throw IllegalStateException("plugin callback is still running")
            }

            override fun onDeleteAborted(botId: BotId) {
                aborted.set(true)
            }

            override fun onCommitted(change: BotConfigurationChange) = Unit
        }
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.randomUUID() },
            changeListener = listener,
        )
        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", true, it)) }

        assertThatThrownBy { notifying.delete(created.id) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("plugin callback is still running")

        assertThat(aborted).isTrue()
        assertThat(repository.deleteCalls).isZero()
        assertThat(repository.findById(created.id)).isNotNull()
    }

    @Test
    fun `durable delete failure releases successful preparation and keeps the bot`() {
        val phases = ArrayList<String>()
        val listener = object : BotConfigurationChangeListener {
            override fun beforeDelete(botId: BotId) {
                phases += "prepared"
            }

            override fun onDeleteAborted(botId: BotId) {
                phases += "aborted"
            }

            override fun onCommitted(change: BotConfigurationChange) = Unit
        }
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.randomUUID() },
            changeListener = listener,
        )
        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", true, it)) }
        repository.deleteFailure = IllegalStateException("database unavailable")

        assertThatThrownBy { notifying.delete(created.id) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("database unavailable")

        assertThat(phases).containsExactly("prepared", "aborted")
        assertThat(repository.findById(created.id)).isNotNull()
    }

    @Test
    fun `failed post delete cleanup is reported after the durable delete and commit notification`() {
        val committed = AtomicBoolean()
        val listener = object : BotConfigurationChangeListener {
            override fun afterDelete(botId: BotId) {
                assertThat(repository.findById(botId)).isNull()
                throw IllegalStateException("plugin tombstone is still in use")
            }

            override fun onCommitted(change: BotConfigurationChange) {
                if (change.kind == BotConfigurationChangeKind.DELETED) committed.set(true)
            }
        }
        val notifying = BotConfigurationService(
            repository,
            cipher,
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.randomUUID() },
            changeListener = listener,
        )
        val created = AppSecret.of(ORIGINAL_SECRET).use { notifying.create(command("102012345", true, it)) }

        assertThatThrownBy { notifying.delete(created.id) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("plugin tombstone is still in use")

        assertThat(repository.findById(created.id)).isNull()
        assertThat(committed).isTrue()
    }

    @Test
    fun `missing master key allows queries but rejects creation and rotation`() {
        val created = create("102012345", false, ORIGINAL_SECRET)
        val unavailable = BotConfigurationService(
            repository,
            AesGcmAppSecretCipher(StaticKeyProvider.unconfigured()),
            clock = Clock.fixed(NOW, ZoneOffset.UTC),
            uuidSupplier = { UUID.fromString("550e8400-e29b-41d4-a716-446655440099") },
        )

        assertThatNoException().isThrownBy { unavailable.findById(created.id) }
        val metadataOnlyUpdate = unavailable.update(
            UpdateBotCommand(
                created.id, created.revision, "Metadata Only", created.appId,
                created.environment, created.intents, created.shardSpec, null,
            ),
        )
        assertThat(metadataOnlyUpdate.revision).isEqualTo(BotRevision.of(2))

        AppSecret.of("cannot-rotate-without-key").use { rotated ->
            val rotation = UpdateBotCommand(
                created.id, BotRevision.of(2), metadataOnlyUpdate.displayName,
                metadataOnlyUpdate.appId, metadataOnlyUpdate.environment,
                metadataOnlyUpdate.intents, metadataOnlyUpdate.shardSpec, rotated,
            )
            assertThatThrownBy { unavailable.update(rotation) }.isInstanceOf(KeyUnavailableException::class.java)
        }
        assertThat(repository.required(created.id).definition.revision).isEqualTo(BotRevision.of(2))

        AppSecret.of("new-secret").use { secret ->
            val create = command("102099999", false, secret)
            assertThatThrownBy { unavailable.create(create) }.isInstanceOf(KeyUnavailableException::class.java)
        }
    }

    @Test
    fun `validates inputs and reports missing bots`() {
        AppSecret.of("valid-secret").use { secret ->
            assertThatIllegalArgumentException().isThrownBy { command("102012345", false, secret, " blank ") }
        }
        val missing = BotId.parse("550e8400-e29b-41d4-a716-446655440099")
        assertThat(service.findById(missing)).isNull()
        assertThatThrownBy { service.enable(missing, BotRevision.initial()) }
            .isInstanceOfSatisfying(BotNotFoundException::class.java) { exception ->
                assertThat(exception.botId).isEqualTo(missing)
            }
    }

    private fun create(appId: String, enabled: Boolean, secretValue: String): BotConfigurationView =
        AppSecret.of(secretValue).use { secret ->
            val command = command(appId, enabled, secret)
            assertThat(command.toString()).contains("<redacted>").doesNotContain(secretValue)
            service.create(command)
        }

    private fun command(appId: String, enabled: Boolean, secret: AppSecret): CreateBotCommand =
        command(appId, enabled, secret, "Example Bot")

    private fun command(
        appId: String,
        enabled: Boolean,
        secret: AppSecret,
        displayName: String,
    ) = CreateBotCommand(
        displayName,
        QqAppId.of(appId),
        BotEnvironment.SANDBOX,
        GatewayIntents.of(512L),
        ShardSpec.single(),
        enabled,
        secret,
    )

    private fun binding(definition: BotDefinition) =
        AppSecretBinding(definition.id, definition.appId, definition.environment)

    private fun revealForTest(secret: AppSecret): String = secret.copyValue().let { characters ->
        try {
            String(characters)
        } finally {
            characters.fill('\u0000')
        }
    }

    private fun keyBytes(value: Int) = ByteArray(32) { value.toByte() }

    private class InMemoryBotRepository : BotRepository {
        private val bots = LinkedHashMap<BotId, StoredBot>()
        var deleteFailure: RuntimeException? = null
        var deleteCalls = 0

        override fun findById(id: BotId): StoredBot? = bots[id]

        override fun findAll(): List<StoredBot> = bots.values.toList()

        override fun findEnabled(): List<StoredBot> = bots.values.filter { it.definition.enabled }

        override fun insert(bot: StoredBot) {
            check(bots.putIfAbsent(bot.id, bot) == null) { "duplicate bot" }
        }

        override fun update(bot: StoredBot, expectedRevision: BotRevision): BotRevision {
            val current = bots[bot.id]
            if (current == null || current.definition.revision != expectedRevision) {
                throw OptimisticLockException(bot.id, expectedRevision)
            }
            require(bot.definition.revision == expectedRevision) { "bot revision must equal expectedRevision" }

            val next = expectedRevision.next()
            val desired = bot.definition
            val persisted = BotDefinition(
                desired.id,
                desired.displayName,
                desired.appId,
                desired.environment,
                desired.intents,
                desired.shardSpec,
                desired.enabled,
                next,
                desired.createdAt,
                desired.updatedAt,
                desired.maxMediaUploadBytes,
            )
            bots[bot.id] = StoredBot(persisted, bot.appSecret)
            return next
        }

        override fun delete(id: BotId): Boolean {
            deleteCalls++
            deleteFailure?.let { throw it }
            return bots.remove(id) != null
        }

        fun required(id: BotId): StoredBot = requireNotNull(findById(id))
    }

    private companion object {
        const val ORIGINAL_SECRET = "original-client-secret"
        val NOW: Instant = Instant.parse("2026-07-16T12:00:00Z")
    }
}
