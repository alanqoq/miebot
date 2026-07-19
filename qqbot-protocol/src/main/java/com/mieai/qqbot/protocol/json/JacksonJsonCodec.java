package com.mieai.qqbot.protocol.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import java.util.Objects;

final class JacksonJsonCodec implements JsonCodec {
    private final ObjectMapper objectMapper;

    JacksonJsonCodec() {
        objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new JsonCodecException("Unable to encode protocol JSON", exception);
        }
    }

    @Override
    public <T> T decode(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new JsonCodecException("Unable to decode protocol JSON as " + type.getName(), exception);
        }
    }

    @Override
    public <T> GatewayEnvelope<T> decodeGatewayEnvelope(String json, Class<T> dataType) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(dataType, "dataType");
        JavaType envelopeType = objectMapper.getTypeFactory()
                .constructParametricType(GatewayEnvelope.class, dataType);
        try {
            return objectMapper.readValue(json, envelopeType);
        } catch (JsonProcessingException exception) {
            throw new JsonCodecException(
                    "Unable to decode Gateway envelope with data type " + dataType.getName(), exception);
        }
    }
}
