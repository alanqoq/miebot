package com.mieai.qqbot.gateway;

import com.mieai.qqbot.client.AccessToken;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import com.mieai.qqbot.protocol.gateway.GatewayHello;
import com.mieai.qqbot.protocol.gateway.GatewayIdentify;
import com.mieai.qqbot.protocol.gateway.GatewayOpcode;
import com.mieai.qqbot.protocol.gateway.GatewayReady;
import com.mieai.qqbot.protocol.gateway.GatewayResume;
import com.mieai.qqbot.protocol.json.JsonCodec;
import java.util.Map;
import java.util.Objects;

final class GatewayProtocolCodec {
    private static final String QQBOT_PREFIX = "QQBot ";

    private final JsonCodec jsonCodec;

    GatewayProtocolCodec(JsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec must not be null");
    }

    DecodedFrame decode(String rawPayload) {
        Objects.requireNonNull(rawPayload, "rawPayload must not be null");
        GatewayEnvelope<Object> envelope =
                jsonCodec.decodeGatewayEnvelope(rawPayload, Object.class);
        GatewayHello hello = null;
        GatewayReady ready = null;
        Boolean invalidSessionResumable = null;
        if (envelope.opcode() == GatewayOpcode.HELLO) {
            hello = jsonCodec.decodeGatewayEnvelope(rawPayload, GatewayHello.class).data();
        } else if (envelope.opcode() == GatewayOpcode.DISPATCH
                && "READY".equals(envelope.eventType())) {
            ready = jsonCodec.decodeGatewayEnvelope(rawPayload, GatewayReady.class).data();
        } else if (envelope.opcode() == GatewayOpcode.INVALID_SESSION) {
            invalidSessionResumable =
                    jsonCodec.decodeGatewayEnvelope(rawPayload, Boolean.class).data();
        }
        return new DecodedFrame(envelope, hello, ready, invalidSessionResumable);
    }

    String encodeIdentify(
            AccessToken token,
            GatewayIntents intents,
            ShardSpec shardSpec,
            Map<String, String> properties) {
        String credential = credential(token);
        GatewayIdentify identify = new GatewayIdentify(
                credential,
                intents.bits(),
                new int[] {shardSpec.index(), shardSpec.count()},
                properties);
        return jsonCodec.encode(new GatewayEnvelope<>(
                GatewayOpcode.IDENTIFY.code(), identify, null, null));
    }

    String encodeResume(AccessToken token, GatewaySessionSnapshot snapshot) {
        String credential = credential(token);
        GatewayResume resume =
                new GatewayResume(credential, snapshot.sessionId(), snapshot.sequence());
        return jsonCodec.encode(new GatewayEnvelope<>(
                GatewayOpcode.RESUME.code(), resume, null, null));
    }

    String encodeHeartbeat(Long sequence) {
        return jsonCodec.encode(new GatewayEnvelope<>(
                GatewayOpcode.HEARTBEAT.code(), sequence, null, null));
    }

    private static String credential(AccessToken token) {
        Objects.requireNonNull(token, "token must not be null");
        String value = token.authorizationHeaderValue();
        if (!value.startsWith(QQBOT_PREFIX) || value.length() == QQBOT_PREFIX.length()) {
            throw new IllegalArgumentException("AccessToken did not provide a QQBot credential");
        }
        return value;
    }

    record DecodedFrame(
            GatewayEnvelope<Object> envelope,
            GatewayHello hello,
            GatewayReady ready,
            Boolean invalidSessionResumable) {}
}
