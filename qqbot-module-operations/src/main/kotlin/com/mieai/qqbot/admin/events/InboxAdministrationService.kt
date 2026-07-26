package com.mieai.qqbot.admin.events

import com.mieai.qqbot.admin.database.DatabaseAdministrationService
import com.mieai.qqbot.admin.database.DatabaseConfigurationView
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.InboxQuery
import com.mieai.qqbot.persistence.inbox.InboxStatus
import java.time.Instant
import java.util.Locale
import java.util.NoSuchElementException
import java.util.UUID
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/** Validates Inbox query input and maps durable events to the administrator API contract. */
@Service
class InboxAdministrationService private constructor(
    private val inboxRepository: EventInboxRepository,
    private val botRepository: BotRepository,
    private val databaseServiceProvider: ObjectProvider<DatabaseAdministrationService>?,
    @Suppress("UNUSED_PARAMETER") normalized: Boolean,
) {
    constructor(
        inboxRepository: EventInboxRepository,
        botRepository: BotRepository,
    ) : this(inboxRepository, botRepository, null, true)

    @Autowired
    constructor(
        inboxRepository: EventInboxRepository,
        botRepository: BotRepository,
        databaseServiceProvider: ObjectProvider<DatabaseAdministrationService>,
    ) : this(inboxRepository, botRepository, databaseServiceProvider, true)

    fun list(
        limit: String?,
        cursor: String?,
        query: String?,
        botId: String?,
        environment: String?,
        status: String?,
        eventType: String?,
    ): InboxPageResponse {
        val inboxQuery = InboxQuery(
            parseLimit(limit),
            optionalText(cursor, "cursor", MAX_CURSOR_LENGTH),
            parseEnvironment(environment),
            parseBotId(botId),
            parseStatus(status),
            optionalToken(eventType, "eventType", MAX_EVENT_TYPE_LENGTH),
            optionalText(query, "query", MAX_QUERY_LENGTH),
        )
        return readConsistently {
            val page = inboxRepository.query(inboxQuery)
            val bots = loadBotMetadata()
            InboxPageResponse(
                page.events.map { summary(it, bots[it.botId]) },
                page.nextCursor,
                page.nextCursor != null,
                Instant.now(),
            )
        }
    }

    fun get(id: String): InboxEventDetailResponse {
        val parsedId = parseUuid(id, "id")
        return readConsistently {
            val event = inboxRepository.findById(parsedId)
                ?: throw NoSuchElementException("Inbox event was not found: $id")
            detail(event, loadBotMetadata()[event.botId])
        }
    }

    /** Prevents one response from combining rows across an in-progress database switch. */
    private fun <T : Any> readConsistently(operation: () -> T): T {
        val databaseService = databaseServiceProvider?.ifAvailable ?: return operation()
        var last: T? = null
        var lastFailure: RuntimeException? = null
        repeat(MAX_CONSISTENCY_ATTEMPTS) {
            val before = databaseService.current()
            var result: T? = null
            var failure: RuntimeException? = null
            try {
                result = operation()
            } catch (exception: RuntimeException) {
                failure = exception
            }
            val after = databaseService.current()
            if (failure == null) last = result else lastFailure = failure
            if (consistent(before, after)) {
                if (failure != null) throw failure
                return requireNotNull(result)
            }
        }
        val terminalFailure = lastFailure
        if (last == null && terminalFailure != null) throw terminalFailure
        return requireNotNull(last)
    }

    private fun loadBotMetadata(): Map<BotId, BotMetadata> =
        botRepository.findAll().associate { stored ->
            stored.id to BotMetadata(
                stored.definition.displayName,
                stored.definition.appId.value,
            )
        }.toMap()

    private data class BotMetadata(val displayName: String, val appId: String)

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        const val MAX_QUERY_LENGTH = 128
        const val MAX_EVENT_TYPE_LENGTH = 128
        const val MAX_CURSOR_LENGTH = 512
        const val MAX_ERROR_CHARACTERS = 1024
        const val MAX_DETAIL_PAYLOAD_BYTES = 1024 * 1024
        private const val MAX_CONSISTENCY_ATTEMPTS = 3

        private fun consistent(
            before: DatabaseConfigurationView,
            after: DatabaseConfigurationView,
        ): Boolean = !before.switchInProgress &&
            !after.switchInProgress &&
            before.revision == after.revision

        private fun summary(event: InboxEvent, bot: BotMetadata?): InboxEventSummaryResponse =
            InboxEventSummaryResponse(
                event.id,
                event.environment.name,
                event.botId.value,
                bot?.displayName,
                bot?.appId,
                event.eventType,
                event.platformEventId,
                event.status.name,
                event.attempt,
                event.availableAt,
                event.receivedAt,
                event.updatedAt,
                limitError(event.lastError),
            )

        private fun detail(event: InboxEvent, bot: BotMetadata?): InboxEventDetailResponse {
            val payload = truncateUtf8(event.payload, MAX_DETAIL_PAYLOAD_BYTES)
            return InboxEventDetailResponse(
                event.id,
                event.environment.name,
                event.botId.value,
                bot?.displayName,
                bot?.appId,
                event.eventType,
                event.platformEventId,
                event.status.name,
                event.attempt,
                event.availableAt,
                event.receivedAt,
                event.updatedAt,
                limitError(event.lastError),
                payload,
                payload != event.payload,
            )
        }

        private fun limitError(value: String?): String? = value?.take(MAX_ERROR_CHARACTERS)

        private fun truncateUtf8(value: String, maxBytes: Int): String {
            var encodedBytes = 0
            var end = 0
            while (end < value.length) {
                val codePoint = value.codePointAt(end)
                val codePointBytes = utf8Bytes(codePoint)
                if (encodedBytes > maxBytes - codePointBytes) break
                encodedBytes += codePointBytes
                end += Character.charCount(codePoint)
            }
            return if (end == value.length) value else value.substring(0, end)
        }

        private fun utf8Bytes(codePoint: Int): Int = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }

        private fun parseLimit(value: String?): Int {
            val candidate = if (value.isNullOrBlank()) DEFAULT_LIMIT.toString() else value.trim()
            try {
                val parsed = candidate.toInt()
                require(parsed in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
                return parsed
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException("limit must be an integer", exception)
            }
        }

        private fun optionalText(value: String?, name: String, maxLength: Int): String? {
            if (value.isNullOrBlank()) return null
            val normalized = value.trim()
            require(normalized.length <= maxLength) { "$name must not exceed $maxLength characters" }
            require(normalized.codePoints().noneMatch(Character::isISOControl)) {
                "$name must not contain control characters"
            }
            return normalized
        }

        private fun optionalToken(value: String?, name: String, maxLength: Int): String? {
            val normalized = optionalText(value, name, maxLength)
            normalized?.let { token ->
                require(token.codePoints().noneMatch(Character::isWhitespace)) {
                    "$name must not contain whitespace"
                }
            }
            return normalized
        }

        private fun parseBotId(value: String?): BotId? =
            optionalText(value, "botId", 64)?.let(BotId::parse)

        private fun parseEnvironment(value: String?): BotEnvironment? =
            optionalText(value, "environment", 32)
                ?.let { parseEnum(it, BotEnvironment::class.java, "environment") }

        private fun parseStatus(value: String?): InboxStatus? =
            optionalText(value, "status", 32)
                ?.let { parseEnum(it, InboxStatus::class.java, "status") }

        private fun <E : Enum<E>> parseEnum(value: String, type: Class<E>, name: String): E = try {
            java.lang.Enum.valueOf(type, value.uppercase(Locale.ROOT))
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("$name is not supported: $value", exception)
        }

        private fun parseUuid(value: String?, name: String): UUID {
            if (value.isNullOrBlank()) throw IllegalArgumentException("$name must not be blank")
            try {
                val parsed = UUID.fromString(value)
                if (!parsed.toString().equals(value, ignoreCase = true)) {
                    throw IllegalArgumentException("$name must use canonical UUID format")
                }
                return parsed
            } catch (exception: IllegalArgumentException) {
                if (exception.message?.contains("canonical") == true) throw exception
                throw IllegalArgumentException("$name must be a canonical UUID", exception)
            }
        }
    }
}
