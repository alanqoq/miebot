package com.mieai.qqbot.admin.error

import java.time.Instant

data class ApiErrorResponse(
    val code: String,
    val message: String,
    val fieldErrors: Map<String, String>,
    val traceId: String,
    val timestamp: Instant,
)
