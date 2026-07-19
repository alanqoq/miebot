package com.mieai.qqbot.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import com.mieai.qqbot.protocol.gateway.GatewayHello;
import com.mieai.qqbot.protocol.gateway.GatewayOpcode;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import org.junit.jupiter.api.Test;

class GatewayContractTest {
    private final JsonCodec codec = JsonCodecs.defaultCodec();

    @Test
    void decodesTypedHelloEnvelopeAndIgnoresExtensions() {
        GatewayEnvelope<GatewayHello> envelope = codec.decodeGatewayEnvelope(
                FixtureLoader.load("/fixtures/gateway/hello.json"), GatewayHello.class);

        assertThat(envelope.op()).isEqualTo(10);
        assertThat(envelope.opcode()).isEqualTo(GatewayOpcode.HELLO);
        assertThat(envelope.data().heartbeatInterval()).isEqualTo(45000L);
        assertThat(envelope.sequence()).isNull();
        assertThat(envelope.eventType()).isNull();
    }

    @Test
    void retainsUnknownOpcodeForForwardCompatibility() {
        GatewayEnvelope<FutureGatewayData> envelope = codec.decodeGatewayEnvelope(
                FixtureLoader.load("/fixtures/gateway/unknown-opcode.json"), FutureGatewayData.class);

        assertThat(envelope.op()).isEqualTo(99);
        assertThat(envelope.opcode()).isEqualTo(GatewayOpcode.UNKNOWN);
        assertThat(envelope.data().future()).isTrue();
    }

    @Test
    void serializesHeartbeatWithoutNullDispatchFields() {
        GatewayEnvelope<Long> heartbeat = new GatewayEnvelope<>(GatewayOpcode.HEARTBEAT.code(), 42L, null, null);

        assertThat(codec.encode(heartbeat)).isEqualTo("{\"op\":1,\"d\":42}");
    }

    @Test
    void mapsAllCurrentlyDefinedGatewayOpcodes() {
        assertThat(GatewayOpcode.fromCode(0)).isEqualTo(GatewayOpcode.DISPATCH);
        assertThat(GatewayOpcode.fromCode(1)).isEqualTo(GatewayOpcode.HEARTBEAT);
        assertThat(GatewayOpcode.fromCode(2)).isEqualTo(GatewayOpcode.IDENTIFY);
        assertThat(GatewayOpcode.fromCode(6)).isEqualTo(GatewayOpcode.RESUME);
        assertThat(GatewayOpcode.fromCode(7)).isEqualTo(GatewayOpcode.RECONNECT);
        assertThat(GatewayOpcode.fromCode(9)).isEqualTo(GatewayOpcode.INVALID_SESSION);
        assertThat(GatewayOpcode.fromCode(10)).isEqualTo(GatewayOpcode.HELLO);
        assertThat(GatewayOpcode.fromCode(11)).isEqualTo(GatewayOpcode.HEARTBEAT_ACK);
        assertThat(GatewayOpcode.fromCode(12)).isEqualTo(GatewayOpcode.HTTP_CALLBACK_ACK);
    }

    private record FutureGatewayData(boolean future) {}
}
