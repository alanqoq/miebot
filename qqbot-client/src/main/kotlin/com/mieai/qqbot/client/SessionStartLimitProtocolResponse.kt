package com.mieai.qqbot.client


data class SessionStartLimitProtocolResponse(
    val total: Int,
    val remaining: Int,
    val reset_after: Long,
    val max_concurrency: Int,
)
