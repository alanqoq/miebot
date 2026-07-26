package com.mieai.qqbot.domain.bot

/** Opaque QQ Gateway intent bit mask that preserves bits unknown to this release. */
data class GatewayIntents(val bits: Long) {
    init {
        require(bits >= 0L) { "bits must not be negative" }
    }

    fun isEmpty(): Boolean = bits == 0L

    fun containsAll(required: GatewayIntents): Boolean = (bits and required.bits) == required.bits

    fun union(other: GatewayIntents): GatewayIntents = of(bits or other.bits)

    fun without(removed: GatewayIntents): GatewayIntents = of(bits and removed.bits.inv())

    companion object {
        val NONE = GatewayIntents(0L)

        val GUILDS = bit(0)

        val GUILD_MEMBERS = bit(1)

        val GUILD_MESSAGES = bit(9)

        val GUILD_MESSAGE_REACTIONS = bit(10)

        val DIRECT_MESSAGE = bit(12)

        val GROUP_MEMBERS = bit(24)

        val GROUP_AND_C2C_EVENT = bit(25)

        val INTERACTION = bit(26)

        val MESSAGE_AUDIT = bit(27)

        val FORUMS_EVENT = bit(28)

        val AUDIO_ACTION = bit(29)

        val PUBLIC_GUILD_MESSAGES = bit(30)

        val DEFAULT_MESSAGE_EVENTS = GROUP_AND_C2C_EVENT

        fun of(bits: Long): GatewayIntents = if (bits == 0L) NONE else GatewayIntents(bits)

        private fun bit(index: Int): GatewayIntents = GatewayIntents(1L shl index)
    }
}
