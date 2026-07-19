package com.mieai.qqbot.protocol.json;

import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;

/** JSON operations exposed without leaking a concrete JSON library. */
public interface JsonCodec {
    String encode(Object value);

    <T> T decode(String json, Class<T> type);

    <T> GatewayEnvelope<T> decodeGatewayEnvelope(String json, Class<T> dataType);
}
