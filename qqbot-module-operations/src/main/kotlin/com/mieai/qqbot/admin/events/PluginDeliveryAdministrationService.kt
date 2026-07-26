package com.mieai.qqbot.admin.events

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginDelivery
import com.mieai.qqbot.persistence.plugin.PluginDeliveryQuery
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository
import com.mieai.qqbot.persistence.plugin.PluginDeliveryStatus
import java.time.Instant
import java.util.Locale
import java.util.NoSuchElementException
import java.util.UUID
import org.springframework.stereotype.Service

/** Read-only management projection for plugin attempts and plugin dead letters. */
@Service
class PluginDeliveryAdministrationService(
    private val deliveries: PluginDeliveryRepository,
    private val bindings: BotPluginBindingRepository,
    private val bots: BotRepository,
    private val inbox: EventInboxRepository,
) {
    fun list(
        limit: String?,
        cursor: String?,
        query: String?,
        bindingId: String?,
        status: String?,
        deadLetterOnly: Boolean,
    ): PluginDeliveryPageResponse {
        val parsedStatus = if (deadLetterOnly) parseDeadLetterStatus(status) else parseStatus(status)
        val request = PluginDeliveryQuery(
            parseLimit(limit),
            optionalText(cursor, "cursor", 512, false),
            optionalUuid(bindingId, "bindingId"),
            if (deadLetterOnly) PluginDeliveryStatus.DEAD_LETTER else parsedStatus,
            optionalText(query, "query", 256, true),
        )
        val page = deliveries.query(request)
        val projection = projection()
        return PluginDeliveryPageResponse(
            page.deliveries.map { summary(it, projection) },
            page.nextCursor,
            page.nextCursor != null,
            Instant.now(),
            PluginDeliveryQueueStatsResponse.from(deliveries.statistics()),
        )
    }

    fun get(id: String, deadLetterOnly: Boolean): PluginDeliveryDetailResponse {
        val parsedId = parseUuid(id, "id")
        val delivery = deliveries.findById(parsedId)
            ?.takeIf { !deadLetterOnly || it.status == PluginDeliveryStatus.DEAD_LETTER }
            ?: throw NoSuchElementException("Plugin delivery was not found: $id")
        val binding = bindings.findById(delivery.bindingId)
            ?: throw NoSuchElementException("Plugin binding was not found")
        val event = inbox.findById(delivery.eventId)
            ?: throw NoSuchElementException("Inbox event was not found")
        val botName = bots.findById(binding.botId)
            ?.definition
            ?.displayName
        return PluginDeliveryDetailResponse(
            delivery.id,
            delivery.eventId,
            delivery.bindingId,
            binding.pluginId,
            binding.botId.value,
            botName,
            delivery.handlerId,
            delivery.status.name,
            delivery.attempt,
            delivery.availableAt,
            delivery.leaseUntil,
            delivery.createdAt,
            delivery.updatedAt,
            delivery.completedAt,
            limitError(delivery.lastError),
            event.eventType,
            event.platformEventId,
            event.receivedAt,
        )
    }

    fun statistics(): PluginDeliveryQueueStatsResponse =
        PluginDeliveryQueueStatsResponse.from(deliveries.statistics())

    private fun projection(): Projection {
        val bindingMap = bindings.findAll().associateBy(BotPluginBinding::id)
        val botNames = bots.findAll().associate { it.id to it.definition.displayName }
        return Projection(bindingMap, botNames)
    }

    private data class Projection(
        val bindings: Map<UUID, BotPluginBinding>,
        val botNames: Map<BotId, String>,
    )

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 100
        const val MAX_ERROR_CHARACTERS = 1024

        fun summary(
            delivery: PluginDelivery,
            projection: Projection,
        ): PluginDeliverySummaryResponse {
            val binding = projection.bindings[delivery.bindingId]
            return PluginDeliverySummaryResponse(
                delivery.id,
                delivery.eventId,
                delivery.bindingId,
                binding?.pluginId,
                binding?.botId?.value,
                binding?.let { projection.botNames[it.botId] },
                delivery.handlerId,
                delivery.status.name,
                delivery.attempt,
                delivery.availableAt,
                delivery.createdAt,
                delivery.updatedAt,
                delivery.completedAt,
                limitError(delivery.lastError),
            )
        }

        fun parseLimit(value: String?): Int {
            val candidate = if (value.isNullOrBlank()) DEFAULT_LIMIT.toString() else value.trim()
            try {
                val parsed = candidate.toInt()
                require(parsed in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT" }
                return parsed
            } catch (exception: NumberFormatException) {
                throw IllegalArgumentException("limit must be an integer", exception)
            }
        }

        fun optionalText(
            value: String?,
            name: String,
            maximumLength: Int,
            allowWhitespace: Boolean,
        ): String? {
            if (value.isNullOrBlank()) return null
            val normalized = value.trim()
            if (
                normalized.length > maximumLength ||
                normalized.codePoints().anyMatch(Character::isISOControl) ||
                (!allowWhitespace && normalized.codePoints().anyMatch(Character::isWhitespace))
            ) {
                throw IllegalArgumentException("$name is invalid")
            }
            return normalized
        }

        fun optionalUuid(value: String?, name: String): UUID? =
            optionalText(value, name, 64, false)?.let { parseUuid(it, name) }

        fun parseStatus(value: String?): PluginDeliveryStatus? =
            optionalText(value, "status", 32, false)?.let { candidate ->
                try {
                    PluginDeliveryStatus.valueOf(candidate.uppercase(Locale.ROOT))
                } catch (exception: IllegalArgumentException) {
                    throw IllegalArgumentException("status is not supported: $candidate", exception)
                }
            }

        fun parseDeadLetterStatus(value: String?): PluginDeliveryStatus? {
            val status = parseStatus(value)
            if (status != null && status != PluginDeliveryStatus.DEAD_LETTER) {
                throw IllegalArgumentException("status must be DEAD_LETTER for the plugin DLQ view")
            }
            return status
        }

        fun parseUuid(value: String?, name: String): UUID {
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

        fun limitError(value: String?): String? = value?.take(MAX_ERROR_CHARACTERS)
    }
}
