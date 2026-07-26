package com.mieai.qqbot.protocol.gateway

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** Data sent with an opcode 2 Identify command. */
@JsonIgnoreProperties(ignoreUnknown = true)
class GatewayIdentify @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("token")
    @get:JsonProperty("token")
    val token: String,
    @param:JsonProperty("intents")
    @get:JsonProperty("intents")
    val intents: Long,
    @param:JsonProperty("shard") shard: IntArray,
    @param:JsonProperty("properties") properties: Map<String, String>,
) {
    private val shardValue = shard.clone()

    @get:JsonProperty("shard")
    val shard: IntArray
        get() = shardValue.clone()

    @get:JsonProperty("properties")
    val properties: Map<String, String> = properties.toMap()

    override fun toString(): String = "GatewayIdentify(token=<redacted>, intents=$intents, shard=<redacted>)"
}
