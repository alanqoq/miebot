package com.mieai.qqbot.protocol.json


/** Factory for protocol JSON codecs. */
object JsonCodecs {
    private val DEFAULT_CODEC: JsonCodec = JacksonJsonCodec()

    fun defaultCodec(): JsonCodec = DEFAULT_CODEC
}
