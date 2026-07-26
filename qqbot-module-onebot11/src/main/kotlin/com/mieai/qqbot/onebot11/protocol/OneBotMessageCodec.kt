package com.mieai.qqbot.onebot11.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode

/** OneBot array/CQ-code conversion without accepting implementation-specific segments. */
class OneBotMessageCodec(private val objectMapper: ObjectMapper) {
    fun parse(message: JsonNode, autoEscape: Boolean): List<OneBotSegment> {
        val result = when {
            message.isTextual -> {
                val value = message.textValue()
                requireBounded(value)
                if (autoEscape) listOf(text(value)) else parseCq(value)
            }
            message.isArray -> parseArray(message)
            else -> throw IllegalArgumentException("message must be a string or segment array")
        }
        require(result.isNotEmpty() && result.size <= MAX_SEGMENTS) {
            "message segment count is invalid"
        }
        return result.toList()
    }

    fun toArray(segments: List<OneBotSegment>): ArrayNode {
        val array = objectMapper.createArrayNode()
        segments.forEach { segment ->
            val value = array.addObject()
            value.put("type", segment.type)
            val data = value.putObject("data")
            segment.data.forEach(data::put)
        }
        return array
    }

    fun toRawMessage(segments: List<OneBotSegment>): String = buildString {
        segments.forEach { segment ->
            if (segment.type == "text") {
                append(escapeText(segment.data.getOrDefault("text", "")))
            } else {
                append("[CQ:").append(segment.type)
                segment.data.forEach { (key, value) ->
                    append(',').append(key).append('=').append(escapeParameter(value))
                }
                append(']')
            }
        }
    }

    private fun parseArray(message: JsonNode): List<OneBotSegment> {
        require(message.size() <= MAX_SEGMENTS) { "message has too many segments" }
        val result = mutableListOf<OneBotSegment>()
        var characters = 0
        message.forEach { value ->
            require(value.isObject && value.path("type").isTextual) { "message segment is invalid" }
            val dataNode = value.path("data")
            require(dataNode.isObject) { "message segment data must be an object" }
            val data = linkedMapOf<String, String>()
            dataNode.properties().forEach { field ->
                require(field.key.matches(DATA_KEY) && field.value.isValueNode) {
                    "message segment data is invalid"
                }
                val text = field.value.asText()
                characters += text.length
                data[field.key] = text
            }
            result += OneBotSegment(value.path("type").textValue(), data)
        }
        require(characters <= MAX_MESSAGE_CHARACTERS) { "message is too long" }
        return result
    }

    private fun parseCq(value: String): List<OneBotSegment> {
        val result = mutableListOf<OneBotSegment>()
        var cursor = 0
        while (cursor < value.length) {
            val start = value.indexOf("[CQ:", cursor)
            if (start < 0) {
                appendText(result, decode(value.substring(cursor)))
                break
            }
            if (start > cursor) {
                appendText(result, decode(value.substring(cursor, start)))
            }
            val end = value.indexOf(']', start + 4)
            if (end < 0) {
                appendText(result, decode(value.substring(start)))
                break
            }
            val body = value.substring(start + 4, end)
            val parts = body.split(",", ignoreCase = false, limit = Int.MAX_VALUE)
            if (parts.isEmpty() || !parts[0].matches(SEGMENT_TYPE)) {
                appendText(result, decode(value.substring(start, end + 1)))
                cursor = end + 1
                continue
            }
            val data = linkedMapOf<String, String>()
            for (index in 1 until parts.size) {
                val equals = parts[index].indexOf('=')
                require(equals >= 1) { "CQ code parameter is invalid" }
                val key = parts[index].substring(0, equals)
                require(key.matches(DATA_KEY)) { "CQ code parameter name is invalid" }
                data[key] = decode(parts[index].substring(equals + 1))
            }
            result += OneBotSegment(parts[0], data)
            cursor = end + 1
            require(result.size <= MAX_SEGMENTS) { "message has too many segments" }
        }
        if (value.isEmpty()) {
            result += text("")
        }
        return result
    }

    companion object {
        private const val MAX_SEGMENTS = 64
        private const val MAX_MESSAGE_CHARACTERS = 32_768
        private val SEGMENT_TYPE = Regex("[a-z][a-z0-9_]{0,63}")
        private val DATA_KEY = Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}")

        fun text(value: String): OneBotSegment = OneBotSegment("text", mapOf("text" to value))

        private fun appendText(target: MutableList<OneBotSegment>, value: String) {
            if (value.isNotEmpty()) {
                target += text(value)
            }
        }

        private fun escapeText(value: String): String = value
            .replace("&", "&amp;")
            .replace("[", "&#91;")
            .replace("]", "&#93;")

        private fun escapeParameter(value: String): String = escapeText(value).replace(",", "&#44;")

        private fun decode(value: String): String = value
            .replace("&#44;", ",")
            .replace("&#91;", "[")
            .replace("&#93;", "]")
            .replace("&amp;", "&")

        private fun requireBounded(value: String) {
            require(value.length <= MAX_MESSAGE_CHARACTERS) { "message is too long" }
        }
    }
}
