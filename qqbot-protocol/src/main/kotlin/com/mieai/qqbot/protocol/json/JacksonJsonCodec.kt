package com.mieai.qqbot.protocol.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.mieai.qqbot.protocol.gateway.GatewayEnvelope

internal class JacksonJsonCodec : JsonCodec {
    private val objectMapper: ObjectMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()
        .apply { setSerializationInclusion(JsonInclude.Include.NON_NULL) }

    override fun encode(value: Any?): String = try {
        objectMapper.writeValueAsString(value)
    } catch (exception: JsonProcessingException) {
        throw JsonCodecException("Unable to encode protocol JSON", exception)
    }

    override fun <T> decode(json: String, type: Class<T>): T {
        return try {
            objectMapper.readValue(json, type)
        } catch (exception: JsonProcessingException) {
            throw JsonCodecException("Unable to decode protocol JSON as ${type.name}", exception)
        }
    }

    override fun <T> decodeGatewayEnvelope(json: String, dataType: Class<T>): GatewayEnvelope<T> {
        val envelopeType = objectMapper.typeFactory.constructParametricType(GatewayEnvelope::class.java, dataType)
        return try {
            objectMapper.readValue(json, envelopeType)
        } catch (exception: JsonProcessingException) {
            throw JsonCodecException("Unable to decode Gateway envelope with data type ${dataType.name}", exception)
        }
    }
}
