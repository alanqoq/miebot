package com.mieai.qqbot.client


data class GatewayBotProtocolResponse(
    val url: String?,
    val shards: Int,
    val session_start_limit: SessionStartLimitProtocolResponse?,
)
