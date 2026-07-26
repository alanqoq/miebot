package com.mieai.qqbot.gateway

import com.mieai.qqbot.client.AccessToken
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.protocol.gateway.GatewayIdentify
import com.mieai.qqbot.protocol.gateway.GatewayOpcode
import com.mieai.qqbot.protocol.gateway.GatewayResume
import com.mieai.qqbot.protocol.json.JsonCodecs
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GatewayProtocolCodecTest {
    private val jsonCodec = JsonCodecs.defaultCodec()
    private val codec = GatewayProtocolCodec(jsonCodec)
    private val token = AccessToken.of("gateway-secret", Instant.parse("2026-07-16T14:00:00Z"))

    @Test
    fun encodesIdentifyWithCurrentQqBotCredentialAndShardProperties() {
        val payload = codec.encodeIdentify(
            token,
            GatewayIntents.of((1L shl 25) or (1L shl 26)),
            ShardSpec(2, 4),
            mapOf("\$os" to "linux", "\$browser" to "test", "\$device" to "test"),
        )

        val envelope = jsonCodec.decodeGatewayEnvelope(payload, GatewayIdentify::class.java)

        assertThat(envelope.opcode()).isEqualTo(GatewayOpcode.IDENTIFY)
        assertThat(envelope.data!!.token).isEqualTo("QQBot gateway-secret")
        assertThat(envelope.data!!.intents).isEqualTo((1L shl 25) or (1L shl 26))
        assertThat(envelope.data!!.shard).containsExactly(2, 4)
        assertThat(envelope.data!!.properties).containsEntry("\$os", "linux")
        assertThat(envelope.data!!.toString()).doesNotContain("gateway-secret")
        assertThat(token.toString()).doesNotContain("gateway-secret")
    }

    @Test
    fun encodesResumeAndHeartbeatWithoutRetainingTokenInSnapshot() {
        val snapshot = GatewaySessionSnapshot("session-123", 42L)
        val resume = jsonCodec.decodeGatewayEnvelope(codec.encodeResume(token, snapshot), GatewayResume::class.java)
        val heartbeat = jsonCodec.decodeGatewayEnvelope(codec.encodeHeartbeat(42L), Long::class.java)

        assertThat(resume.opcode()).isEqualTo(GatewayOpcode.RESUME)
        assertThat(resume.data!!.token).isEqualTo("QQBot gateway-secret")
        assertThat(resume.data!!.sessionId).isEqualTo("session-123")
        assertThat(resume.data!!.sequence).isEqualTo(42L)
        assertThat(resume.data!!.toString()).doesNotContain("gateway-secret")
        assertThat(heartbeat.opcode()).isEqualTo(GatewayOpcode.HEARTBEAT)
        assertThat(heartbeat.data).isEqualTo(42L)
        assertThat(snapshot.toString()).doesNotContain("gateway-secret")
    }

    @Test
    fun decodesHelloInvalidSessionAndUnknownOpcodeWithFutureFields() {
        val hello = codec.decode("{\"op\":10,\"d\":{\"heartbeat_interval\":45000,\"future\":1},\"future\":true}")
        val invalid = codec.decode("{\"op\":9,\"d\":true,\"future\":true}")
        val unknown = codec.decode("{\"op\":99,\"d\":{\"future\":true},\"s\":7}")

        assertThat(hello.envelope.opcode()).isEqualTo(GatewayOpcode.HELLO)
        assertThat(hello.hello?.heartbeatInterval).isEqualTo(45_000L)
        assertThat(invalid.invalidSessionResumable).isTrue()
        assertThat(unknown.envelope.opcode()).isEqualTo(GatewayOpcode.UNKNOWN)
        assertThat(unknown.envelope.op).isEqualTo(99)
        assertThat(unknown.envelope.sequence).isEqualTo(7L)
    }
}
