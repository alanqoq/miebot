package com.mieai.qqbot.gateway

/** Persistable subset required to resume a Gateway session. */
data class GatewaySessionSnapshot(val sessionId: String, val sequence: Long) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(sequence >= 0L) { "sequence must not be negative" }
    }

    fun withSequence(nextSequence: Long): GatewaySessionSnapshot = when {
        nextSequence <= sequence -> this
        else -> GatewaySessionSnapshot(sessionId, nextSequence)
    }
}
