package com.mieai.qqbot.gateway

import com.mieai.qqbot.protocol.event.QqEventData
import com.mieai.qqbot.protocol.event.QqEventDecoder
import com.mieai.qqbot.protocol.json.JsonCodecs
import java.util.Locale

/** Raw dispatch offered to the durable-ingress hook before the session advances its sequence. */
data class GatewayDispatch private constructor(
    val sequence: Long,
    val eventType: String,
    val rawPayload: String,
    val platformEventId: String?,
    val sessionId: String?,
    private val normalized: Boolean,
) {
    private constructor(values: NormalizedValues) : this(
        values.sequence,
        values.eventType,
        values.rawPayload,
        values.platformEventId,
        values.sessionId,
        true,
    )

    constructor(
        sequence: Long,
        eventType: String,
        rawPayload: String,
        platformEventId: String? = null,
        sessionId: String? = null,
    ) : this(normalizeValues(sequence, eventType, rawPayload, platformEventId, sessionId))

    /** Returns a typed data object for current official events, or null for a future event type. */
    fun decodeKnownEvent(): QqEventData? = EVENT_DECODER.decodeKnown(eventType, rawPayload)

    companion object {
        private val EVENT_DECODER = QqEventDecoder.defaultDecoder()

        private fun normalizeValues(
            sequence: Long,
            eventType: String,
            rawPayload: String,
            platformEventId: String?,
            sessionId: String?,
        ): NormalizedValues {
            require(sequence >= 0L) { "sequence must not be negative" }
            val checkedPlatformEventId = platformEventId
                ?.takeUnless(String::isBlank)
                ?: extractPlatformEventId(eventType, rawPayload)
            val checkedSessionId = sessionId?.takeUnless(String::isBlank)
            if (checkedSessionId != null &&
                checkedSessionId.codePoints().anyMatch(Character::isWhitespace)
            ) {
                throw IllegalArgumentException("sessionId must not contain whitespace")
            }
            return NormalizedValues(
                sequence,
                eventType,
                rawPayload,
                checkedPlatformEventId,
                checkedSessionId,
            )
        }

        private fun extractPlatformEventId(eventType: String, rawPayload: String): String? {
            try {
                val envelope = JsonCodecs.defaultCodec()
                    .decodeGatewayEnvelope(rawPayload, Any::class.java)
                normalizedValue(envelope.eventId)?.let { return it }
                val data = envelope.data
                if (data is Map<*, *>) {
                    // Bare resource IDs are not event-scoped, unlike explicit event_id fields.
                    for (key in arrayOf("event_id", "eventId")) {
                        normalizedValue(data[key])?.let { return it }
                    }
                    if (messageLike(eventType)) {
                        for (key in arrayOf("id", "msg_id", "message_id")) {
                            normalizedValue(data[key])?.let { return it }
                        }
                    }
                }
            } catch (_: RuntimeException) {
                // Opaque future payloads may not expose an event identifier yet.
            }
            return null
        }

        private fun messageLike(eventType: String): Boolean {
            val normalized = eventType.uppercase(Locale.ROOT)
            return normalized.contains("MESSAGE") ||
                normalized.contains("INTERACTION") ||
                normalized.contains("REACTION")
        }

        private fun normalizedValue(value: Any?): String? {
            if (value == null) {
                return null
            }
            val candidate = value.toString().trim()
            if (candidate.isEmpty() ||
                candidate.codePoints().anyMatch(Character::isISOControl) ||
                candidate.codePoints().anyMatch(Character::isWhitespace)
            ) {
                return null
            }
            return candidate
        }
    }

    private data class NormalizedValues(
        val sequence: Long,
        val eventType: String,
        val rawPayload: String,
        val platformEventId: String?,
        val sessionId: String?,
    )
}
