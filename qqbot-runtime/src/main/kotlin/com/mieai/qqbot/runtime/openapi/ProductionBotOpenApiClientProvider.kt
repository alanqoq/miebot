package com.mieai.qqbot.runtime.openapi

import com.mieai.qqbot.client.BotCredentials
import com.mieai.qqbot.client.QqAccessTokenClient
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqOpenApiClient
import com.mieai.qqbot.client.SingleFlightTokenProvider
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor

/** Revision-aware client cache shared by modules that call bot-scoped QQ OpenAPI operations. */
class ProductionBotOpenApiClientProvider(
    private val bots: BotRepository,
    secretCipher: AppSecretCipher,
    private val optionsResolver: (BotEnvironment) -> QqClientOptions,
) : BotOpenApiClientProvider {
    private val credentials = BotCredentialDecryptor(secretCipher)
    private val clients = HashMap<BotId, ClientHandle>()
    private var closed = false

    @Synchronized
    override fun clientFor(botId: BotId): QqOpenApiClient {
        ensureOpen()
        val bot = bots.findById(botId) ?: throw BotNotFoundException(botId)
        val current = clients[botId]
        val revision = bot.definition.revision.value
        if (current != null &&
            current.revision == revision &&
            current.environment == bot.definition.environment
        ) {
            return current.client
        }

        val baseOptions = optionsResolver(bot.definition.environment)
        val options = baseOptions.copy(maxMediaBytes = bot.definition.maxMediaUploadBytes)

        val decrypted = credentials.decrypt(bot)
        try {
            val tokenProvider = SingleFlightTokenProvider(
                decrypted,
                QqAccessTokenClient(options),
                options,
            )
            val client = QqOpenApiClient(options, tokenProvider, decrypted.appId)
            clients[botId] = ClientHandle(
                revision,
                bot.definition.environment,
                client,
                decrypted,
            )
            closeQuietly(current)
            return client
        } catch (exception: RuntimeException) {
            decrypted.close()
            throw exception
        }
    }

    @Synchronized
    override fun invalidate(botId: BotId) {
        closeQuietly(clients.remove(botId))
    }

    @Synchronized
    override fun invalidateAll() {
        clients.values.forEach(::closeQuietly)
        clients.clear()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        invalidateAll()
    }

    private fun ensureOpen() {
        check(!closed) { "The bot OpenAPI client provider is closed" }
    }

    private fun closeQuietly(handle: ClientHandle?) {
        if (handle == null) return
        try {
            handle.credentials.close()
        } catch (_: RuntimeException) {
            // Continue invalidating the remaining bot clients.
        }
    }

    private data class ClientHandle(
        val revision: Long,
        val environment: BotEnvironment,
        val client: QqOpenApiClient,
        val credentials: BotCredentials,
    )
}
