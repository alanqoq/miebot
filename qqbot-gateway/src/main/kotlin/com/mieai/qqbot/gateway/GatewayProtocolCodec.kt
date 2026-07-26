package com.mieai.qqbot.gateway

import com.mieai.qqbot.client.AccessToken
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.protocol.gateway.GatewayEnvelope
import com.mieai.qqbot.protocol.gateway.GatewayHello
import com.mieai.qqbot.protocol.gateway.GatewayIdentify
import com.mieai.qqbot.protocol.gateway.GatewayOpcode
import com.mieai.qqbot.protocol.gateway.GatewayReady
import com.mieai.qqbot.protocol.gateway.GatewayResume
import com.mieai.qqbot.protocol.json.JsonCodec

class GatewayProtocolCodec(private val jsonCodec: JsonCodec) {

    fun decode(rawPayload: String): DecodedFrame {
        val envelope = jsonCodec.decodeGatewayEnvelope(rawPayload, Any::class.java)
        var hello: GatewayHello? = null
        var ready: GatewayReady? = null
        var invalidSessionResumable: Boolean? = null
        when {
            envelope.opcode() == GatewayOpcode.HELLO -> {
                hello = jsonCodec.decodeGatewayEnvelope(rawPayload, GatewayHello::class.java).data
            }

            envelope.opcode() == GatewayOpcode.DISPATCH && envelope.eventType == "READY" -> {
                ready = jsonCodec.decodeGatewayEnvelope(rawPayload, GatewayReady::class.java).data
            }

            envelope.opcode() == GatewayOpcode.INVALID_SESSION -> {
                invalidSessionResumable = jsonCodec.decodeGatewayEnvelope(
                    rawPayload,
                    Boolean::class.javaObjectType,
                ).data
            }
        }
        return DecodedFrame(envelope, hello, ready, invalidSessionResumable)
    }

    fun encodeIdentify(
        token: AccessToken,
        intents: GatewayIntents,
        shardSpec: ShardSpec,
        properties: Map<String, String>,
    ): String {
        val identify = GatewayIdentify(
            credential(token),
            intents.bits,
            intArrayOf(shardSpec.index, shardSpec.count),
            properties,
        )
        return jsonCodec.encode(
            GatewayEnvelope(GatewayOpcode.IDENTIFY.code(), identify, null, null),
        )
    }

    fun encodeResume(token: AccessToken, snapshot: GatewaySessionSnapshot): String {
        val resume = GatewayResume(credential(token), snapshot.sessionId, snapshot.sequence)
        return jsonCodec.encode(
            GatewayEnvelope(GatewayOpcode.RESUME.code(), resume, null, null),
        )
    }

    fun encodeHeartbeat(sequence: Long?): String = jsonCodec.encode(
        GatewayEnvelope(GatewayOpcode.HEARTBEAT.code(), sequence, null, null),
    )

    private fun credential(token: AccessToken): String {
        val value = token.authorizationHeaderValue()
        require(value.startsWith(QQBOT_PREFIX) && value.length > QQBOT_PREFIX.length) {
            "AccessToken did not provide a QQBot credential"
        }
        return value
    }

    data class DecodedFrame(
        val envelope: GatewayEnvelope<Any>,
        val hello: GatewayHello?,
        val ready: GatewayReady?,
        val invalidSessionResumable: Boolean?,
    )

    companion object {
        private const val QQBOT_PREFIX = "QQBot "
    }
}
