package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.BotCredentials
import com.mieai.qqbot.client.MediaAsset
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqAccessTokenClient
import com.mieai.qqbot.client.QqClientException
import com.mieai.qqbot.client.QqClientFailure
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqMediaMessageRequest
import com.mieai.qqbot.client.QqMessageSendOptions
import com.mieai.qqbot.client.QqMessageSendResult
import com.mieai.qqbot.client.QqOpenApiClient
import com.mieai.qqbot.client.QqRichMessageRequest
import com.mieai.qqbot.client.QqTextMessageRequest
import com.mieai.qqbot.client.SingleFlightTokenProvider
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.outbox.OutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxRepository
import com.mieai.qqbot.persistence.outbox.OutboxSendReceipt
import com.mieai.qqbot.protocol.openapi.QqMessageModels
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/** Claims durable message jobs and sends them through bot-scoped QQ OpenAPI clients. */
class ProductionOutboxWorker(
    private val outbox: OutboxRepository,
    private val bots: BotRepository,
    secretCipher: AppSecretCipher,
    private val optionsResolver: (BotEnvironment) -> QqClientOptions,
    private val mapper: ObjectMapper,
    private val scheduler: ScheduledExecutorService,
    private val clock: Clock,
    pollInterval: Duration,
    leaseDuration: Duration,
    requestWait: Duration,
    private val maxAttempts: Int,
    private val batchSize: Int,
    private val botLeases: BotLeaseRepository? = null,
    private val instanceId: String? = null,
    private val mediaStore: MediaAssetStore? = null,
) : AutoCloseable {
    private val credentials = BotCredentialDecryptor(secretCipher)
    private val pollInterval = positive(pollInterval, "pollInterval")
    private val leaseDuration = positive(leaseDuration, "leaseDuration")
    private val requestWait = positive(requestWait, "requestWait")
    private val workerId = "outbox-worker-${UUID.randomUUID()}"
    private val running = AtomicBoolean()
    private val transitionMonitor = Any()
    private val clients = HashMap<BotId, ClientHandle>()

    @Volatile
    private var databaseTransitionActive = false
    private var polling: ScheduledFuture<*>? = null

    init {
        require(this.leaseDuration > this.requestWait) { "leaseDuration must exceed requestWait" }
        require(maxAttempts >= 1) { "maxAttempts must be positive" }
        require(batchSize in 1..1000) { "batchSize is invalid" }
        require(botLeases == null || instanceId != null) { "instanceId must be provided when bot leases are enabled" }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        polling = scheduler.scheduleWithFixedDelay(
            ::tickSafely,
            0,
            pollInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
    }

    fun isRunning(): Boolean = running.get()

    fun beforeActiveDatabaseChange() {
        synchronized(transitionMonitor) {
            databaseTransitionActive = true
            clearClients()
        }
    }

    fun activeDatabaseChanged() {
        synchronized(transitionMonitor) {
            clearClients()
            databaseTransitionActive = false
        }
    }

    private fun tickSafely() {
        if (!running.get() || databaseTransitionActive) return
        synchronized(transitionMonitor) {
            if (!running.get() || databaseTransitionActive) return
            try {
                var index = 0
                while (index < batchSize && processOne()) index++
            } catch (exception: RuntimeException) {
                LOGGER.warn("Outbox poll failed ({})", exception.javaClass.simpleName)
            }
        }
    }

    private fun processOne(): Boolean {
        val now = clock.instant()
        val claimed = if (botLeases == null) {
            outbox.claimNext(workerId, now, leaseDuration)
        } else {
            outbox.claimNextOwned(workerId, instanceId!!, now, leaseDuration)
        }
        val job = claimed ?: return false
        var stagedAsset: MediaAsset? = null
        try {
            if (job.jobType != OutboundTextPayload.JOB_TYPE &&
                job.jobType != OutboundMediaPayload.JOB_TYPE &&
                job.jobType != OutboundRichPayload.JOB_TYPE
            ) {
                outbox.markDeadLetter(
                    job.id,
                    job.fencingToken,
                    clock.instant(),
                    "Unsupported Outbox job type",
                )
                return true
            }
            val bot = bots.findById(job.botId)
                ?: throw PermanentFailure("Bot no longer exists")
            if (botLeases != null &&
                !botLeases.isOwned(
                    bot.definition.id,
                    bot.definition.shardSpec.index,
                    instanceId!!,
                    clock.instant(),
                )
            ) {
                throw RetryableFailure("Bot lease is no longer owned by this instance")
            }
            if (!bot.definition.enabled) throw RetryableFailure("Bot is disabled")
            if (bot.definition.environment != job.environment) {
                throw PermanentFailure("Outbox environment does not match bot environment")
            }

            val sent = when (job.jobType) {
                OutboundTextPayload.JOB_TYPE -> sendText(bot, job)
                OutboundMediaPayload.JOB_TYPE -> sendMedia(bot, job) { asset -> stagedAsset = asset }
                else -> sendRich(bot, job)
            }
            outbox.markSucceeded(job.id, job.fencingToken, clock.instant(), receipt(sent))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            retry(job, exception)
        } catch (exception: TimeoutException) {
            resultUnknown(job, "QQ request timed out after it may have been sent")
        } catch (exception: ExecutionException) {
            classify(job, unwrap(exception))
        } catch (exception: CompletionException) {
            classify(job, unwrap(exception))
        } catch (exception: UnconfirmedSendResult) {
            resultUnknown(job, safeError(exception))
        } catch (exception: PermanentFailure) {
            deadLetter(job, safeError(exception))
        } catch (exception: IllegalArgumentException) {
            deadLetter(job, safeError(exception))
        } catch (exception: IOException) {
            deadLetter(job, safeError(exception))
        } catch (exception: RetryableFailure) {
            retry(job, exception)
        } catch (exception: RuntimeException) {
            retry(job, exception)
        } finally {
            stagedAsset?.let { asset ->
                try {
                    val status = outbox.findById(job.id)?.status
                    if (status != null && status.isTerminal()) mediaStore?.delete(asset)
                } catch (cleanupFailure: RuntimeException) {
                    LOGGER.warn(
                        "Could not clean staged media asset for job {} ({})",
                        job.id,
                        cleanupFailure.javaClass.simpleName,
                    )
                }
            }
        }
        return true
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    private fun sendText(bot: StoredBot, job: OutboxJob): QqMessageSendResult {
        val payload = mapper.readValue(job.payload, OutboundTextPayload::class.java)
        val sendOptions = sendOptions(payload.messageReference)
        val request = QqTextMessageRequest(
            payload.targetType,
            payload.targetId,
            payload.content,
            payload.replyMessageId,
            payload.replyEventId,
            payload.messageSequence,
        )
        return client(bot).sendText(request, sendOptions).toCompletableFuture()
            .get(requestWait.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    private fun sendMedia(
        bot: StoredBot,
        job: OutboxJob,
        onStagedAsset: (MediaAsset) -> Unit,
    ): QqMessageSendResult {
        val payload = mapper.readValue(job.payload, OutboundMediaPayload::class.java)
        val sendOptions = sendOptions(payload.messageReference)
        val request = QqMediaMessageRequest(
            payload.targetType,
            payload.targetId,
            payload.mediaKind,
            URI.create(payload.mediaUrl),
            payload.content,
            payload.replyMessageId,
            payload.replyEventId,
            payload.messageSequence,
        )
        if (payload.mediaAssetId == null) {
            val sending: CompletionStage<QqMessageSendResult> = if (mediaStore == null) {
                client(bot).sendMedia(request, sendOptions)
            } else {
                client(bot).sendMediaBounded(request, sendOptions)
            }
            return sending.toCompletableFuture().get(requestWait.toMillis(), TimeUnit.MILLISECONDS)
        }

        val store = mediaStore ?: throw PermanentFailure("Local media staging is not configured")
        val asset = store.find(bot.definition.id, payload.mediaAssetId)
            ?: throw PermanentFailure("Staged media asset is no longer available")
        onStagedAsset(asset)
        if (asset.kind != request.mediaKind) {
            throw PermanentFailure("Staged media kind does not match the Outbox payload")
        }
        if (asset.sizeBytes > bot.definition.maxMediaUploadBytes) {
            throw PermanentFailure("Staged media exceeds the bot upload limit")
        }
        val bytes = store.open(asset).use { input ->
            input.readNBytes(Math.toIntExact(bot.definition.maxMediaUploadBytes + 1L))
        }
        if (bytes.size > bot.definition.maxMediaUploadBytes) {
            throw PermanentFailure("Staged media exceeds the bot upload limit")
        }
        return client(bot).sendMedia(request, bytes, sendOptions).toCompletableFuture()
            .get(requestWait.toMillis(), TimeUnit.MILLISECONDS)
    }

    @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
    private fun sendRich(bot: StoredBot, job: OutboxJob): QqMessageSendResult {
        val payload = mapper.readValue(job.payload, OutboundRichPayload::class.java)
        val sendOptions = sendOptions(payload.messageReference)
        val request = QqRichMessageRequest(
            payload.targetType,
            payload.targetId,
            payload.kind,
            payload.payload,
            payload.replyMessageId,
            payload.replyEventId,
            payload.messageSequence,
        )
        return client(bot).sendRich(request, sendOptions).toCompletableFuture()
            .get(requestWait.toMillis(), TimeUnit.MILLISECONDS)
    }

    private fun sendOptions(reference: OutboundMessageReference?): QqMessageSendOptions =
        QqMessageSendOptions(
            messageReference = reference?.let {
                QqMessageModels.MessageReference(it.messageId, it.ignoreGetMessageError)
            },
        )

    private fun client(bot: StoredBot): QqOpenApiClient {
        val id = bot.definition.id
        val current = clients[id]
        val revision = bot.definition.revision.value
        if (current != null &&
            current.revision == revision &&
            current.environment == bot.definition.environment
        ) {
            return current.client
        }
        current?.close()
        val decrypted = credentials.decrypt(bot)
        try {
            val options = optionsResolver(bot.definition.environment)
                .copy(maxMediaBytes = bot.definition.maxMediaUploadBytes)
            val provider = SingleFlightTokenProvider(
                decrypted,
                QqAccessTokenClient(options),
                options,
            )
            val client = QqOpenApiClient(options, provider, decrypted.appId)
            clients[id] = ClientHandle(revision, bot.definition.environment, client, decrypted)
            return client
        } catch (exception: RuntimeException) {
            decrypted.close()
            throw exception
        }
    }

    private fun receipt(sent: QqMessageSendResult?): OutboxSendReceipt {
        if (sent == null) throw UnconfirmedSendResult("QQ response has no message result")
        val platformMessageId = sent.id
        if (platformMessageId.isNullOrBlank()) throw UnconfirmedSendResult("QQ response has no message ID")
        try {
            val messageSequence = sent.msgSeq?.takeIf { it > 0 }
            return OutboxSendReceipt(
                platformMessageId,
                messageSequence,
                optionalToken(sent.timestamp, 128),
            )
        } catch (_: IllegalArgumentException) {
            throw UnconfirmedSendResult("QQ response has an invalid message ID")
        }
    }

    private fun optionalToken(value: String?, maxCharacters: Int): String? {
        if (value == null ||
            value.isBlank() ||
            value != value.trim() ||
            value.codePoints().anyMatch { Character.isWhitespace(it) } ||
            value.codePoints().anyMatch { Character.isISOControl(it) } ||
            value.codePointCount(0, value.length) > maxCharacters
        ) {
            return null
        }
        return value
    }

    private fun classify(job: OutboxJob, failure: Throwable) {
        if (failure is IllegalArgumentException) {
            deadLetter(job, safeError(failure))
            return
        }
        if (failure !is QqClientException) {
            retry(job, failure)
            return
        }
        if (failure.failure == QqClientFailure.TIMEOUT ||
            failure.failure == QqClientFailure.PROTOCOL
        ) {
            resultUnknown(job, safeError(failure))
            return
        }
        if (failure.failure == QqClientFailure.TRANSPORT ||
            failure.failure == QqClientFailure.AUTHENTICATION
        ) {
            retry(job, failure)
            return
        }
        val status = failure.httpStatus ?: 0
        if (status == 408 || status == 409 || status == 425 || status == 429 || status >= 500) {
            retry(job, failure)
        } else {
            deadLetter(job, safeError(failure))
        }
    }

    private fun retry(job: OutboxJob, failure: Throwable) {
        val now = clock.instant()
        val error = safeError(failure)
        try {
            if (job.attempt >= maxAttempts) {
                outbox.markDeadLetter(job.id, job.fencingToken, now, error)
            } else {
                outbox.markRetry(
                    job.id,
                    job.fencingToken,
                    now,
                    now.plus(backoff(job.attempt)),
                    error,
                )
            }
        } catch (transition: RuntimeException) {
            LOGGER.warn(
                "Could not retry Outbox job {} ({})",
                job.id,
                transition.javaClass.simpleName,
            )
        }
    }

    private fun deadLetter(job: OutboxJob, reason: String) {
        try {
            outbox.markDeadLetter(job.id, job.fencingToken, clock.instant(), reason)
        } catch (transition: RuntimeException) {
            LOGGER.warn(
                "Could not dead-letter Outbox job {} ({})",
                job.id,
                transition.javaClass.simpleName,
            )
        }
    }

    private fun resultUnknown(job: OutboxJob, reason: String) {
        try {
            outbox.markResultUnknown(job.id, job.fencingToken, clock.instant(), reason)
        } catch (transition: RuntimeException) {
            LOGGER.warn(
                "Could not mark Outbox job {} result unknown ({})",
                job.id,
                transition.javaClass.simpleName,
            )
        }
    }

    private fun clearClients() {
        clients.values.forEach(ClientHandle::close)
        clients.clear()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        polling?.cancel(false)
        synchronized(transitionMonitor) { clearClients() }
    }

    private data class ClientHandle(
        val revision: Long,
        val environment: BotEnvironment,
        val client: QqOpenApiClient,
        val credentials: BotCredentials,
    ) : AutoCloseable {
        override fun close() = credentials.close()
    }

    private class PermanentFailure(message: String) : RuntimeException(message)
    private class RetryableFailure(message: String) : RuntimeException(message)
    private class UnconfirmedSendResult(message: String) : RuntimeException(message)

    private companion object {
        val LOGGER = LoggerFactory.getLogger(ProductionOutboxWorker::class.java)

        fun backoff(attempt: Long): Duration {
            val exponent = minOf(maxOf(attempt - 1L, 0L), 8L).toInt()
            return Duration.ofSeconds(minOf(300L, 1L shl exponent))
        }

        fun unwrap(failure: Throwable): Throwable {
            var current = failure
            while ((current is CompletionException || current is ExecutionException) &&
                current.cause != null
            ) {
                current = current.cause!!
            }
            return current
        }

        fun safeError(failure: Throwable): String {
            val current = unwrap(failure)
            var value = current.javaClass.simpleName
            if (current is QqClientException) {
                value += "[${current.failure}]"
                current.httpStatus?.let { value += " HTTP $it" }
                current.qqCode?.let { value += " QQ $it" }
            } else if (!current.message.isNullOrBlank()) {
                value += ": ${current.message!!.replace('\n', ' ').replace('\r', ' ')}"
            }
            return if (value.length > 512) value.substring(0, 512) else value
        }

        fun positive(value: Duration, name: String): Duration {
            require(!value.isZero && !value.isNegative) { "$name must be positive" }
            return value
        }
    }
}
