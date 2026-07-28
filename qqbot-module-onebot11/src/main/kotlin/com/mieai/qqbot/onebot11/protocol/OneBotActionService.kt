package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMediaMessageRequest
import com.mieai.qqbot.client.QqMessageSendResult
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.client.QqTextMessageRequest
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository
import com.mieai.qqbot.onebot11.mapping.OneBotStoredMessage
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider
import com.mieai.qqbot.runtime.supervisor.BotRuntimeState
import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** OneBot action dispatcher for the QQ official C2C/group compatibility subset. */
class OneBotActionService(
    private val objectMapper: ObjectMapper,
    private val clients: BotOpenApiClientProvider,
    private val bots: BotRepository,
    private val supervisor: BotSupervisor,
    private val entityIds: OneBotEntityIdRepository,
    private val messages: OneBotMessageRepository,
    private val messageCodec: OneBotMessageCodec,
    private val mediaCache: OneBotMediaCache,
    private val eventMapper: OneBotEventMapper,
) : AutoCloseable {
    private val actions: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
        daemon(runnable, "onebot11-action")
    }
    private val rateLimited: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        runnable -> daemon(runnable, "onebot11-rate-limited")
    }
    private val nextRateLimitedAt = AtomicLong()
    private val messageSequences = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var restartHandler: (BotId) -> Unit = { }

    fun setRestartHandler(handler: (BotId) -> Unit) {
        restartHandler = handler
    }

    fun handle(botId: BotId, payload: String?): CompletionStage<String> {
        if (payload == null || payload.length > MAX_REQUEST_CHARACTERS) {
            return CompletableFuture.completedFuture(encode(failed(null, 1400, "Invalid request")))
        }
        val request = try {
            objectMapper.readTree(payload) as? ObjectNode
                ?: throw IllegalArgumentException("request must be a JSON object")
        } catch (_: Exception) {
            return CompletableFuture.completedFuture(encode(failed(null, 1400, "Invalid JSON")))
        }

        val echo = if (request.has("echo")) request.get("echo") else null
        val requestedAction = request.path("action").takeIf(JsonNode::isTextual)?.textValue()
        if (requestedAction.isNullOrBlank()) {
            return CompletableFuture.completedFuture(
                encode(failed(echo, 1400, "action is required")),
            )
        }
        val asynchronous = requestedAction.endsWith("_async")
        val limited = requestedAction.endsWith("_rate_limited")
        val action = when {
            asynchronous -> requestedAction.removeSuffix("_async")
            limited -> requestedAction.removeSuffix("_rate_limited")
            else -> requestedAction
        }
        var params: JsonNode = request.path("params")
        if (params.isMissingNode || params.isNull) {
            params = objectMapper.createObjectNode()
        }
        if (!params.isObject) {
            return CompletableFuture.completedFuture(
                encode(failed(echo, 1400, "params must be an object")),
            )
        }

        if (!isSupportedAction(action)) {
            return CompletableFuture.completedFuture(
                encode(failed(echo, 1404, "Unsupported action")),
            )
        }
        if (asynchronous || limited || action == "set_restart") {
            val operation = { executeSafely(botId, action, params) }
            if (limited) {
                scheduleRateLimited(operation)
            } else {
                actions.execute { operation() }
            }
            return CompletableFuture.completedFuture(encode(async(echo)))
        }
        return CompletableFuture.supplyAsync(
            { encode(execute(botId, action, params, echo)) },
            actions,
        )
    }

    private fun execute(botId: BotId, action: String, params: JsonNode, echo: JsonNode?): ObjectNode =
        try {
            val data = when (action) {
                "send_private_msg" -> send(botId, QqMessageTargetType.C2C, params)
                "send_group_msg" -> send(botId, QqMessageTargetType.GROUP, params)
                "send_msg" -> sendInferred(botId, params)
                "delete_msg" -> deleteMessage(botId, params)
                "get_msg" -> getMessage(botId, params)
                "get_login_info" -> loginInfo(botId)
                "get_image" -> getImage(botId, params)
                "get_record" -> getRecord(botId, params)
                "can_send_image", "can_send_record" -> objectMapper.createObjectNode().put("yes", true)
                "get_status" -> status(botId)
                "get_version_info" -> versionInfo()
                "set_restart" -> restart(botId)
                "clean_cache" -> cleanCache(botId)
                else -> throw UnsupportedActionException()
            }
            ok(echo, data)
        } catch (_: UnsupportedActionException) {
            failed(echo, 1404, "Unsupported action")
        } catch (exception: IllegalArgumentException) {
            failed(echo, 1400, safeMessage(exception, "Invalid parameters"))
        } catch (_: RuntimeException) {
            failed(echo, 1200, "QQ operation failed")
        }

    private fun executeSafely(botId: BotId, action: String, params: JsonNode) {
        execute(botId, action, params, null)
    }

    private fun sendInferred(botId: BotId, params: JsonNode): JsonNode {
        val type = optionalText(params, "message_type") ?: when {
            params.hasNonNull("group_id") -> "group"
            params.hasNonNull("user_id") -> "private"
            else -> null
        }
        return when (type) {
            "private" -> send(botId, QqMessageTargetType.C2C, params)
            "group" -> send(botId, QqMessageTargetType.GROUP, params)
            else -> throw IllegalArgumentException("message_type is invalid")
        }
    }

    private fun send(botId: BotId, targetType: QqMessageTargetType, params: JsonNode): JsonNode {
        val group = targetType == QqMessageTargetType.GROUP
        val targetAlias = requiredLong(params, if (group) "group_id" else "user_id")
        val target = entityIds.require(
            botId,
            targetAlias,
            if (group) OneBotEntityType.GROUP else OneBotEntityType.USER,
        )
        if (!group && target.scopeId.isNotEmpty()) {
            throw IllegalArgumentException("A group-scoped user cannot be used for private messages")
        }
        val messageNode = params.get("message")
            ?: throw IllegalArgumentException("message is required")
        val segments = messageCodec.parse(
            messageNode,
            optionalBoolean(params, "auto_escape", false),
        )
        val compiled = compile(botId, targetType, target.rawId, segments)
        val client = clients.clientFor(botId)
        val sequence = nextSequence(botId, targetType, target.rawId)
        val sent: QqMessageSendResult
        if (compiled.media == null) {
            require(compiled.content.isNotBlank()) { "message has no sendable content" }
            sent = await(
                client.sendText(
                    QqTextMessageRequest(
                        targetType,
                        target.rawId,
                        compiled.content,
                        compiled.replyMessageId,
                        null,
                        sequence,
                    ),
                ),
            )
        } else {
            val media = compiled.media
            val mediaRequest = QqMediaMessageRequest(
                targetType,
                target.rawId,
                media.kind,
                media.uri,
                compiled.content.takeIf(String::isNotBlank),
                compiled.replyMessageId,
                null,
                sequence,
            )
            sent = if (media.bytes == null) {
                await(client.sendMediaBounded(mediaRequest))
            } else {
                await(client.sendMedia(mediaRequest, media.bytes))
            }
        }
        val sentId = sent.id
        if (sentId.isNullOrBlank()) {
            throw IllegalStateException("QQ response has no message ID")
        }
        val selfId = eventMapper.selfId(botId)
        val nickname = (bots.findById(botId)
            ?: throw IllegalArgumentException("Bot does not exist"))
            .definition
            .displayName
        val sender = objectMapper.createObjectNode().apply {
            put("user_id", selfId)
            put("nickname", nickname)
            put("sex", "unknown")
            put("age", 0)
        }
        val oneBotMessageId = messages.record(
            botId,
            sentId,
            targetType,
            target.rawId,
            OneBotStoredMessage.Direction.OUTGOING,
            if (group) "group" else "private",
            parseTime(sent.timestamp),
            selfId,
            encode(messageCodec.toArray(segments)),
            encode(sender),
        )
        return objectMapper.createObjectNode().put("message_id", oneBotMessageId)
    }

    private fun compile(
        botId: BotId,
        targetType: QqMessageTargetType,
        targetRawId: String,
        segments: List<OneBotSegment>,
    ): CompiledMessage {
        val content = StringBuilder()
        var media: OutboundMedia? = null
        var reply: String? = null
        segments.forEach { segment ->
            when (segment.type) {
                "text" -> content.append(segment.data.getOrDefault("text", ""))
                "face" -> content.append("<emoji:").append(requiredData(segment, "id")).append('>')
                "at" -> {
                    require(targetType == QqMessageTargetType.GROUP) {
                        "at is only supported in group messages"
                    }
                    val alias = parsePositiveLong(requiredData(segment, "qq"), "at qq")
                    val user = entityIds.require(botId, alias, OneBotEntityType.USER)
                    require(user.scopeId == targetRawId) { "at user is not mapped in this group" }
                    content.append("<@").append(user.rawId).append('>')
                }
                "reply" -> {
                    require(reply == null) { "Only one reply segment is supported" }
                    val id = Math.toIntExact(
                        parsePositiveLong(requiredData(segment, "id"), "reply id"),
                    )
                    val referenced = messages.find(botId, id)
                        ?: throw IllegalArgumentException("Reply message is unknown")
                    require(
                        referenced.targetType == targetType &&
                            referenced.targetRawId == targetRawId,
                    ) { "Reply message belongs to another conversation" }
                    reply = referenced.officialMessageId
                }
                "image", "record", "video" -> {
                    require(media == null) { "Only one media segment is supported per message" }
                    media = outboundMedia(segment)
                }
                else -> throw IllegalArgumentException("Unsupported message segment: ${segment.type}")
            }
        }
        return CompiledMessage(content.toString(), media, reply)
    }

    private fun outboundMedia(segment: OneBotSegment): OutboundMedia {
        var file = segment.data["file"]
        if (file.isNullOrBlank()) {
            file = segment.data["url"]
        }
        require(!file.isNullOrBlank()) { "media file is required" }
        val kind = when (segment.type) {
            "image" -> QqMediaKind.IMAGE
            "record" -> QqMediaKind.AUDIO
            "video" -> QqMediaKind.VIDEO
            else -> throw IllegalArgumentException("media type is invalid")
        }
        if (file.startsWith("base64://")) {
            val bytes = try {
                Base64.getDecoder().decode(file.removePrefix("base64://"))
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("media base64 is invalid", exception)
            }
            require(bytes.isNotEmpty() && bytes.size <= MAX_INLINE_MEDIA_BYTES) {
                "media base64 size is invalid"
            }
            return OutboundMedia(kind, URI.create("https://onebot.invalid/media"), bytes)
        }
        val uri = try {
            URI.create(file)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("media file must be HTTPS or base64", exception)
        }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "media file must be HTTPS or base64"
        }
        return OutboundMedia(kind, uri, null)
    }

    private fun deleteMessage(botId: BotId, params: JsonNode): JsonNode {
        val messageId = Math.toIntExact(requiredLong(params, "message_id"))
        val message = messages.find(botId, messageId)
            ?: throw IllegalArgumentException("message_id is unknown")
        await(
            clients.clientFor(botId).recallMessage(
                message.targetType,
                message.targetRawId,
                message.officialMessageId,
                false,
            ),
        )
        return NullNode.getInstance()
    }

    private fun getMessage(botId: BotId, params: JsonNode): JsonNode {
        val messageId = Math.toIntExact(requiredLong(params, "message_id"))
        val message = messages.find(botId, messageId)
            ?: throw IllegalArgumentException("message_id is unknown")
        return objectMapper.createObjectNode().apply {
            put("time", message.eventTime)
            put("message_type", message.messageType)
            put("message_id", message.messageId)
            put("real_id", message.messageId)
            set<JsonNode>("sender", decode(message.senderJson))
            set<JsonNode>("message", decode(message.messageJson))
            if (message.targetType == QqMessageTargetType.GROUP) {
                put(
                    "group_id",
                    entityIds.aliasFor(
                        botId,
                        OneBotEntityType.GROUP,
                        "",
                        message.targetRawId,
                    ),
                )
            }
        }
    }

    private fun loginInfo(botId: BotId): JsonNode {
        val bot = bots.findById(botId) ?: throw IllegalArgumentException("Bot does not exist")
        return objectMapper.createObjectNode().apply {
            put("user_id", eventMapper.selfId(botId))
            put("nickname", bot.definition.displayName)
        }
    }

    private fun getImage(botId: BotId, params: JsonNode): JsonNode = objectMapper
        .createObjectNode()
        .put("file", mediaCache.image(botId, requiredText(params, "file")).toString())

    private fun getRecord(botId: BotId, params: JsonNode): JsonNode = objectMapper
        .createObjectNode()
        .put(
            "file",
            mediaCache.record(
                botId,
                requiredText(params, "file"),
                requiredText(params, "out_format"),
            ).toString(),
        )

    private fun status(botId: BotId): JsonNode {
        val online = supervisor.status(botId)?.state == BotRuntimeState.ONLINE
        return objectMapper.createObjectNode().apply {
            put("online", online)
            put("good", online)
        }
    }

    private fun versionInfo(): JsonNode = objectMapper.createObjectNode().apply {
        put("app_name", "mirai-qqbot-onebot11")
        put("app_version", "1.0.1")
        put("protocol_version", "v11")
        put("compatibility", "qq-official-c2c-group-subset")
    }

    private fun restart(botId: BotId): JsonNode {
        restartHandler(botId)
        return NullNode.getInstance()
    }

    private fun cleanCache(botId: BotId): JsonNode {
        mediaCache.clean(botId)
        return NullNode.getInstance()
    }

    private fun scheduleRateLimited(operation: () -> Unit) {
        val now = System.currentTimeMillis()
        val scheduled = nextRateLimitedAt.updateAndGet { previous ->
            maxOf(now, previous) + RATE_LIMIT_INTERVAL_MS
        }
        rateLimited.schedule({ operation() }, maxOf(0L, scheduled - now), TimeUnit.MILLISECONDS)
    }

    private fun ok(echo: JsonNode?, data: JsonNode?): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("status", "ok")
            put("retcode", 0)
            set<JsonNode>("data", data ?: NullNode.getInstance())
            setEcho(this, echo)
        }

    private fun async(echo: JsonNode?): ObjectNode = objectMapper.createObjectNode().apply {
        put("status", "async")
        put("retcode", 1)
        set<JsonNode>("data", NullNode.getInstance())
        setEcho(this, echo)
    }

    private fun failed(echo: JsonNode?, retcode: Int, message: String): ObjectNode =
        objectMapper.createObjectNode().apply {
            put("status", "failed")
            put("retcode", retcode)
            set<JsonNode>("data", NullNode.getInstance())
            put("message", message)
            put("wording", message)
            setEcho(this, echo)
        }

    private fun decode(value: String): JsonNode = try {
        objectMapper.readTree(value)
    } catch (exception: Exception) {
        throw IllegalStateException("Stored OneBot JSON is invalid", exception)
    }

    private fun encode(value: Any): String = try {
        objectMapper.writeValueAsString(value)
    } catch (exception: Exception) {
        throw IllegalStateException("Unable to encode OneBot JSON", exception)
    }

    private fun nextSequence(botId: BotId, type: QqMessageTargetType, targetId: String): Int {
        val sequence = messageSequences.computeIfAbsent("$botId:$type:$targetId") { AtomicInteger() }
        return sequence.updateAndGet { previous -> if (previous == Int.MAX_VALUE) 1 else previous + 1 }
    }

    override fun close() {
        actions.shutdownNow()
        rateLimited.shutdownNow()
    }

    private data class OutboundMedia(val kind: QqMediaKind, val uri: URI, val bytes: ByteArray?)

    private data class CompiledMessage(
        val content: String,
        val media: OutboundMedia?,
        val replyMessageId: String?,
    )

    private class UnsupportedActionException : RuntimeException()

    companion object {
        private const val MAX_REQUEST_CHARACTERS = 1_048_576
        private const val MAX_INLINE_MEDIA_BYTES = 256 * 1024 * 1024
        private const val RATE_LIMIT_INTERVAL_MS = 500L
        private val SUPPORTED_ACTIONS = setOf(
            "send_private_msg",
            "send_group_msg",
            "send_msg",
            "delete_msg",
            "get_msg",
            "get_login_info",
            "get_image",
            "get_record",
            "can_send_image",
            "can_send_record",
            "get_status",
            "get_version_info",
            "set_restart",
            "clean_cache",
        )

        private fun isSupportedAction(action: String): Boolean = action in SUPPORTED_ACTIONS

        private fun setEcho(result: ObjectNode, echo: JsonNode?) {
            if (echo != null) {
                result.set<JsonNode>("echo", echo.deepCopy())
            }
        }

        private fun <T> await(stage: CompletionStage<T>): T = try {
            stage.toCompletableFuture().get(30, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("QQ operation was interrupted", exception)
        } catch (exception: ExecutionException) {
            throw IllegalStateException("QQ operation failed", exception)
        } catch (exception: TimeoutException) {
            throw IllegalStateException("QQ operation failed", exception)
        }

        private fun requiredLong(params: JsonNode, name: String): Long {
            val value = params.get(name)
            if (value == null || value.isNull) {
                throw IllegalArgumentException("$name is required")
            }
            if (value.isIntegralNumber) {
                val result = value.longValue()
                require(result >= 1L) { "$name must be positive" }
                return result
            }
            if (value.isTextual) {
                return parsePositiveLong(value.textValue(), name)
            }
            throw IllegalArgumentException("$name must be a number")
        }

        private fun parsePositiveLong(value: String, name: String): Long = try {
            value.toLong().also { result ->
                if (result < 1L) throw NumberFormatException()
            }
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("$name must be a positive integer", exception)
        }

        private fun requiredText(params: JsonNode, name: String): String =
            optionalText(params, name) ?: throw IllegalArgumentException("$name is required")

        private fun optionalText(params: JsonNode, name: String): String? {
            val value = params.get(name)
            if (value == null || value.isNull) return null
            require(value.isTextual && value.textValue().isNotBlank()) {
                "$name must be non-blank text"
            }
            return value.textValue()
        }

        private fun optionalBoolean(params: JsonNode, name: String, defaultValue: Boolean): Boolean {
            val value = params.get(name)
            if (value == null || value.isNull) return defaultValue
            if (value.isBoolean) return value.booleanValue()
            if (value.isTextual && (value.textValue() == "true" || value.textValue() == "false")) {
                return value.textValue().toBoolean()
            }
            throw IllegalArgumentException("$name must be a boolean")
        }

        private fun requiredData(segment: OneBotSegment, name: String): String {
            val value = segment.data[name]
            require(!value.isNullOrBlank()) { "${segment.type} $name is required" }
            return value
        }

        private fun parseTime(value: String?): Long {
            if (value != null) {
                try {
                    return Instant.parse(value).epochSecond
                } catch (_: DateTimeParseException) {
                    // Fall back to the local receipt time.
                }
            }
            return Instant.now().epochSecond
        }

        private fun safeMessage(exception: RuntimeException, fallback: String): String {
            val message = exception.message
            return if (message.isNullOrBlank() || message.length > 240) fallback else message
        }

        private fun daemon(runnable: Runnable, name: String): Thread =
            Thread(runnable, name).apply { isDaemon = true }
    }
}
