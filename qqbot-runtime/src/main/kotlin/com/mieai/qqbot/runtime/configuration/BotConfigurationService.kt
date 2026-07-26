package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.OptimisticLockException
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.runtime.security.AppSecret
import com.mieai.qqbot.runtime.security.AppSecretBinding
import com.mieai.qqbot.runtime.security.AppSecretCipher
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/** Application service for persisted bot configuration and credential changes. */
class BotConfigurationService(
    private val repository: BotRepository,
    private val secretCipher: AppSecretCipher,
    private val clock: Clock = Clock.systemUTC(),
    private val uuidSupplier: () -> UUID = { UUID.randomUUID() },
    private val changeListener: BotConfigurationChangeListener = BotConfigurationChangeListener.none(),
) {
    fun create(command: CreateBotCommand): BotConfigurationView {
        val id = BotId.of(uuidSupplier())
        val now = clock.instant()
        val definition = BotDefinition(
            id,
            command.displayName,
            command.appId,
            command.environment,
            command.intents,
            command.shardSpec,
            command.enabled,
            BotRevision.initial(),
            now,
            now,
            command.maxMediaUploadBytes,
        )
        val encryptedSecret = secretCipher.encrypt(command.appSecret, binding(definition))
        repository.insert(StoredBot(definition, encryptedSecret))
        val created = toView(definition)
        notifyCommitted(created, BotConfigurationChangeKind.CREATED)
        return created
    }

    fun findById(botId: BotId): BotConfigurationView? =
        repository.findById(botId)?.let { stored -> toView(stored.definition) }

    fun findAll(): List<BotConfigurationView> =
        repository.findAll().map { stored -> toView(stored.definition) }

    fun findEnabled(): List<BotConfigurationView> =
        repository.findEnabled().map { stored -> toView(stored.definition) }

    fun update(command: UpdateBotCommand): BotConfigurationView {
        val current = required(command.botId)
        requireRevision(current, command.expectedRevision)

        val currentDefinition = current.definition
        val desired = BotDefinition(
            currentDefinition.id,
            command.displayName,
            command.appId,
            command.environment,
            command.intents,
            command.shardSpec,
            currentDefinition.enabled,
            command.expectedRevision,
            currentDefinition.createdAt,
            updateTime(currentDefinition),
            command.maxMediaUploadBytes ?: currentDefinition.maxMediaUploadBytes,
        )
        val encryptedSecret = selectSecret(current, desired, command.appSecret)
        val updated = persistUpdate(desired, encryptedSecret, command.expectedRevision)
        notifyCommitted(updated, BotConfigurationChangeKind.UPDATED)
        return updated
    }

    fun enable(botId: BotId, expectedRevision: BotRevision): BotConfigurationView =
        setEnabled(botId, expectedRevision, true)

    fun disable(botId: BotId, expectedRevision: BotRevision): BotConfigurationView =
        setEnabled(botId, expectedRevision, false)

    fun delete(botId: BotId) {
        val current = required(botId)
        try {
            changeListener.beforeDelete(botId)
            if (!repository.delete(botId)) throw BotNotFoundException(botId)
        } catch (failure: RuntimeException) {
            try {
                changeListener.onDeleteAborted(botId)
            } catch (abortFailure: RuntimeException) {
                failure.addSuppressed(abortFailure)
            }
            throw failure
        }

        var finalizationFailure: RuntimeException? = null
        try {
            changeListener.afterDelete(botId)
        } catch (failure: RuntimeException) {
            finalizationFailure = failure
        }
        notifyCommitted(
            BotConfigurationChange(
                botId,
                current.definition.revision,
                false,
                BotConfigurationChangeKind.DELETED,
            ),
        )
        if (finalizationFailure != null) throw finalizationFailure
    }

    private fun setEnabled(
        botId: BotId,
        expectedRevision: BotRevision,
        enabled: Boolean,
    ): BotConfigurationView {
        val current = required(botId)
        requireRevision(current, expectedRevision)
        val existing = current.definition
        val desired = BotDefinition(
            existing.id,
            existing.displayName,
            existing.appId,
            existing.environment,
            existing.intents,
            existing.shardSpec,
            enabled,
            expectedRevision,
            existing.createdAt,
            updateTime(existing),
            existing.maxMediaUploadBytes,
        )
        val updated = persistUpdate(desired, current.appSecret, expectedRevision)
        notifyCommitted(
            updated,
            if (enabled) BotConfigurationChangeKind.ENABLED else BotConfigurationChangeKind.DISABLED,
        )
        return updated
    }

    private fun selectSecret(
        current: StoredBot,
        desired: BotDefinition,
        requestedSecret: AppSecret?,
    ): SecretCiphertext {
        val desiredBinding = binding(desired)
        if (requestedSecret != null) {
            return secretCipher.encrypt(requestedSecret, desiredBinding)
        }

        val currentBinding = binding(current.definition)
        if (currentBinding == desiredBinding) return current.appSecret

        secretCipher.decrypt(current.appSecret, currentBinding).use { decrypted ->
            return secretCipher.encrypt(decrypted, desiredBinding)
        }
    }

    private fun persistUpdate(
        desired: BotDefinition,
        encryptedSecret: SecretCiphertext,
        expectedRevision: BotRevision,
    ): BotConfigurationView {
        val updatedRevision = repository.update(StoredBot(desired, encryptedSecret), expectedRevision)
        return toView(
            BotDefinition(
                desired.id,
                desired.displayName,
                desired.appId,
                desired.environment,
                desired.intents,
                desired.shardSpec,
                desired.enabled,
                updatedRevision,
                desired.createdAt,
                desired.updatedAt,
                desired.maxMediaUploadBytes,
            ),
        )
    }

    private fun required(botId: BotId): StoredBot =
        repository.findById(botId) ?: throw BotNotFoundException(botId)

    private fun requireRevision(bot: StoredBot, expectedRevision: BotRevision) {
        if (bot.definition.revision != expectedRevision) {
            throw OptimisticLockException(bot.definition.id, expectedRevision)
        }
    }

    private fun updateTime(current: BotDefinition): Instant {
        val now = clock.instant()
        return if (now.isBefore(current.updatedAt)) current.updatedAt else now
    }

    private fun binding(definition: BotDefinition): AppSecretBinding =
        AppSecretBinding(definition.id, definition.appId, definition.environment)

    private fun toView(definition: BotDefinition): BotConfigurationView =
        BotConfigurationView(
            definition.id,
            definition.displayName,
            definition.appId,
            definition.environment,
            definition.intents,
            definition.shardSpec,
            definition.enabled,
            definition.revision,
            definition.createdAt,
            definition.updatedAt,
            true,
            definition.maxMediaUploadBytes,
        )

    private fun notifyCommitted(view: BotConfigurationView, kind: BotConfigurationChangeKind) {
        notifyCommitted(BotConfigurationChange(view.id, view.revision, view.enabled, kind))
    }

    private fun notifyCommitted(change: BotConfigurationChange) {
        try {
            changeListener.onCommitted(change)
        } catch (exception: RuntimeException) {
            // Persistence already committed; runtime reconciliation has a periodic full-scan fallback.
            LOGGER.warn(
                "Bot configuration notification failed for {} at revision {} ({})",
                change.botId,
                change.revision.value,
                exception.javaClass.simpleName,
            )
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(BotConfigurationService::class.java)
    }
}
