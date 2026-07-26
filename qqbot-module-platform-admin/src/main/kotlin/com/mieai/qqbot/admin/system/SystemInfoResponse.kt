package com.mieai.qqbot.admin.system

import java.time.Instant

data class SystemInfoResponse(
    val service: String,
    val version: String,
    val status: String,
    val database: String,
    val serverTime: Instant,
)
