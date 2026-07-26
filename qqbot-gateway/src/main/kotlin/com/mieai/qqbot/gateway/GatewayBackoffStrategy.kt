package com.mieai.qqbot.gateway

import java.time.Duration

/** Injectable reconnect delay policy. Attempts are one-based. */
fun interface GatewayBackoffStrategy {
    fun delayForAttempt(attempt: Int, cause: GatewayReconnectCause): Duration

    companion object {
        fun fixed(delay: Duration): GatewayBackoffStrategy {
            val checked = requireNonNegative(delay, "delay")
            return GatewayBackoffStrategy { attempt, _ ->
                requireAttempt(attempt)
                checked
            }
        }

        fun exponential(initial: Duration, maximum: Duration): GatewayBackoffStrategy =
            exponential(initial, maximum) { it }

        /**
         * Creates bounded exponential backoff and applies an injectable jitter policy to each delay.
         * The jitter result must stay between zero and the calculated exponential delay.
         */
        fun exponential(
            initial: Duration,
            maximum: Duration,
            jitter: (Duration) -> Duration,
        ): GatewayBackoffStrategy {
            val checkedInitial = requireNonNegative(initial, "initial")
            val checkedMaximum = requireNonNegative(maximum, "maximum")
            require(checkedInitial <= checkedMaximum) { "initial must not exceed maximum" }
            return GatewayBackoffStrategy { attempt, _ ->
                requireAttempt(attempt)
                var result = checkedInitial
                for (index in 1 until attempt) {
                    if (result >= checkedMaximum) {
                        break
                    }
                    result = try {
                        result.multipliedBy(2L)
                    } catch (_: ArithmeticException) {
                        checkedMaximum
                    }
                }
                val bounded = if (result > checkedMaximum) checkedMaximum else result
                val jittered = jitter(bounded)
                require(!jittered.isNegative && jittered <= bounded) {
                    "jitter must return a delay between zero and the exponential delay"
                }
                jittered
            }
        }

        private fun requireAttempt(attempt: Int) {
            require(attempt > 0) { "attempt must be positive" }
        }

        private fun requireNonNegative(duration: Duration, name: String): Duration {
            require(!duration.isNegative) { "$name must not be negative" }
            return duration
        }
    }
}
