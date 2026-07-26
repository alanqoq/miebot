package com.mieai.qqbot.onebot11.protocol

/** OneBot message segment with an immutable data snapshot. */
class OneBotSegment(type: String, data: Map<String, String>) {
    val type: String = type.also {
        require(it.matches(SEGMENT_TYPE)) { "message segment type is invalid" }
    }
    val data: Map<String, String> = data.toMap()

    override fun equals(other: Any?): Boolean =
        this === other || (other is OneBotSegment && type == other.type && data == other.data)

    override fun hashCode(): Int = 31 * type.hashCode() + data.hashCode()

    override fun toString(): String = "OneBotSegment[type=$type, data=$data]"

    companion object {
        private val SEGMENT_TYPE = Regex("[a-z][a-z0-9_]{0,63}")
    }
}
