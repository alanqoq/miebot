package com.mieai.qqbot.admin.health

import java.time.Instant

data class HealthStatus(
    val status: String,
    val checkedAt: Instant,
    val components: Map<String, String>,
)
