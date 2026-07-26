package com.mieai.qqbot.protocol.gateway

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.protocol.user.CurrentBotUser

/** Data carried by the READY dispatch event. */
@JsonIgnoreProperties(ignoreUnknown = true)
class GatewayReady @JsonCreator(mode = JsonCreator.Mode.PROPERTIES) constructor(
    @param:JsonProperty("version")
    @get:JsonProperty("version")
    val version: Int,
    @param:JsonProperty("session_id")
    @get:JsonProperty("session_id")
    val sessionId: String,
    @param:JsonProperty("user")
    @get:JsonProperty("user")
    val user: CurrentBotUser,
    @param:JsonProperty("shard") shard: IntArray,
) {
    private val shardValue = shard.clone()

    @get:JsonProperty("shard")
    val shard: IntArray
        get() = shardValue.clone()
}
