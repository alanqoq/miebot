package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.internal.DatabaseExceptionClassifier
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingOptimisticLockException
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.springframework.http.HttpStatus
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class PluginBindingAdministrationService(
    private val bindings: BotPluginBindingRepository,
    private val artifacts: PluginArtifactRepository,
    private val bots: BotRepository,
    private val host: Pf4jPluginHost,
    private val runtime: PluginRuntimeService,
    private val files: PluginBindingFileService,
) {
    private val clock = Clock.systemUTC()

    fun list(pluginId: String?, botId: String?): List<PluginBindingResponse> {
        val parsedBot = botId?.takeIf { it.isNotBlank() }?.let(BotId::parse)
        return bindings.findAll()
            .asSequence()
            .filter { pluginId.isNullOrBlank() || it.pluginId == pluginId }
            .filter { parsedBot == null || it.botId == parsedBot }
            .map(PluginBindingResponse::from)
            .toList()
    }

    fun create(request: CreatePluginBindingRequest): PluginBindingResponse {
        val botId = BotId.parse(request.botId)
        if (bots.findById(botId) == null) throw notFound("BOT_NOT_FOUND", "Bot does not exist")
        if (artifacts.findById(request.pluginId) == null || !host.isLoaded(request.pluginId)) {
            throw notFound("PLUGIN_NOT_LOADED", "Plugin is not loaded")
        }
        if (bindings.findByPluginAndBot(request.pluginId, botId) != null) {
            throw failure(HttpStatus.CONFLICT, "BINDING_EXISTS", "This plugin is already bound to the bot")
        }

        if (request.hasConflictingConfigurationFields()) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG", "Configuration fields do not match")
        }
        val suppliedConfiguration = request.suppliedConfiguration()
        if (suppliedConfiguration.isNullOrBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "INVALID_PLUGIN_CONFIG", "Plugin configuration must not be blank")
        }
        val configuration = files.validatedConfiguration(request.pluginId, suppliedConfiguration)
        val now = clock.instant()
        val binding = BotPluginBinding(
            UUID.randomUUID(),
            request.pluginId,
            botId,
            false,
            0L,
            now,
            now,
            PluginBindingRuntimeState.PAUSED,
            null,
        )
        try {
            bindings.insert(binding)
        } catch (exception: DataAccessException) {
            if (DatabaseExceptionClassifier.isDuplicateKey(exception)) {
                throw failure(HttpStatus.CONFLICT, "BINDING_EXISTS", "This plugin is already bound to the bot")
            }
            throw exception
        }

        try {
            files.initialize(binding, configuration)
        } catch (exception: RuntimeException) {
            runCatching {
                files.withDataDirectoryTombstoned(binding) { bindings.delete(binding.id) }
            }.exceptionOrNull()?.let(exception::addSuppressed)
            throw exception
        }

        val saved = if (request.enabled) {
            val active = BotPluginBinding(
                binding.id,
                binding.pluginId,
                binding.botId,
                true,
                binding.revision,
                binding.createdAt,
                clock.instant(),
                PluginBindingRuntimeState.ACTIVE,
                null,
            )
            try {
                bindings.update(active, binding.revision)
            } catch (exception: PluginBindingOptimisticLockException) {
                runtime.bindingChanged(binding.id)
                throw failure(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.message ?: "Binding revision conflict")
            }
        } else {
            binding
        }
        runtime.bindingChanged(saved.id)
        return PluginBindingResponse.from(saved)
    }

    fun update(id: UUID, request: UpdatePluginBindingRequest): PluginBindingResponse {
        val current = bindings.findById(id)
            ?: throw notFound("BINDING_NOT_FOUND", "Plugin binding does not exist")
        val state = when {
            !request.enabled -> PluginBindingRuntimeState.PAUSED
            current.runtimeState == PluginBindingRuntimeState.QUARANTINED -> PluginBindingRuntimeState.QUARANTINED
            else -> PluginBindingRuntimeState.ACTIVE
        }
        val runtimeError = if (state == PluginBindingRuntimeState.QUARANTINED) {
            current.runtimeError
        } else {
            null
        }
        val changed = BotPluginBinding(
            current.id,
            current.pluginId,
            current.botId,
            request.enabled,
            request.expectedRevision,
            current.createdAt,
            clock.instant(),
            state,
            runtimeError,
        )
        return try {
            val saved = bindings.update(changed, request.expectedRevision)
            runtime.bindingChanged(id)
            PluginBindingResponse.from(saved)
        } catch (exception: PluginBindingOptimisticLockException) {
            throw failure(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.message ?: "Binding revision conflict")
        }
    }

    fun delete(id: UUID) {
        val current = bindings.findById(id)
            ?: throw notFound("BINDING_NOT_FOUND", "Plugin binding does not exist")
        try {
            files.withDataDirectoryTombstoned(current) { bindings.delete(id) }
        } catch (exception: RuntimeException) {
            runCatching { runtime.bindingChanged(id) }.exceptionOrNull()?.let(exception::addSuppressed)
            throw exception
        }
        runtime.bindingChanged(id)
    }

    fun reset(id: UUID): PluginBindingResponse {
        val current = bindings.findById(id)
            ?: throw notFound("BINDING_NOT_FOUND", "Plugin binding does not exist")
        if (!current.enabled) {
            throw failure(HttpStatus.CONFLICT, "BINDING_DISABLED", "Enable the plugin binding before resetting its quarantine")
        }
        files.requireValidConfiguration(current)
        runtime.resetQuarantinedBinding(id)
        return PluginBindingResponse.from(checkNotNull(bindings.findById(id)))
    }

    private fun notFound(code: String, message: String) = failure(HttpStatus.NOT_FOUND, code, message)

    private fun failure(status: HttpStatus, code: String, message: String) =
        PluginAdministrationException(status, code, message)
}
