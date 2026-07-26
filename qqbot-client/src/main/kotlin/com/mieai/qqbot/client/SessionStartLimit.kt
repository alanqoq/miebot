package com.mieai.qqbot.client

import java.time.Duration

/** Session creation budget returned by `GET /gateway/bot`. */
data class SessionStartLimit(
    val total: Int,
    val remaining: Int,
    val resetAfter: Duration,
    val maxConcurrency: Int,
) {
    init {
        require(total >= 0) { "total must not be negative" }
        require(remaining in 0..total) { "remaining must be between zero and total" }
        require(!resetAfter.isNegative) { "resetAfter must not be negative" }
        require(maxConcurrency > 0) { "maxConcurrency must be positive" }
    }
}
