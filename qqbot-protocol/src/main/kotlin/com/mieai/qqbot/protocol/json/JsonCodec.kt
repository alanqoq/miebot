package com.mieai.qqbot.protocol.json

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope

/** JSON operations exposed without leaking a concrete JSON library. */
interface JsonCodec {
    fun encode(value: Any?): String

    fun <T> decode(json: String, type: Class<T>): T

    fun <T> decodeGatewayEnvelope(json: String, dataType: Class<T>): GatewayEnvelope<T>
}
