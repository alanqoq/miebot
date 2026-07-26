package com.mieai.qqbot.gateway

import java.time.Duration

internal class RecordingBackoffStrategy(private val delay: Duration) : GatewayBackoffStrategy {
    private val attemptsValue = mutableListOf<Int>()
    private val causesValue = mutableListOf<GatewayReconnectCause>()

    override fun delayForAttempt(attempt: Int, cause: GatewayReconnectCause): Duration {
        attemptsValue += attempt
        causesValue += cause
        return delay
    }

    fun attempts(): List<Int> = attemptsValue.toList()

    fun causes(): List<GatewayReconnectCause> = causesValue.toList()
}
