package com.mieai.qqbot.domain.bot


/** Monotonically increasing optimistic-lock revision for bot configuration. */
data class BotRevision(val value: Long) : Comparable<BotRevision> {
    init {
        require(value >= 1L) { "value must be positive" }
    }

    fun next(): BotRevision = BotRevision(Math.incrementExact(value))

    override fun compareTo(other: BotRevision): Int = value.compareTo(other.value)

    companion object {
        private val INITIAL = BotRevision(1L)

        fun initial(): BotRevision = INITIAL

        fun of(value: Long): BotRevision = if (value == 1L) INITIAL else BotRevision(value)
    }
}
