package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class GatewayIntentsTest {
    @Test
    fun exposesTheCurrentOfficialIntentBitsAndSafeDefault() {
        assertThat(GatewayIntents.GUILDS.bits).isEqualTo(1L shl 0)
        assertThat(GatewayIntents.GUILD_MEMBERS.bits).isEqualTo(1L shl 1)
        assertThat(GatewayIntents.GUILD_MESSAGES.bits).isEqualTo(1L shl 9)
        assertThat(GatewayIntents.GUILD_MESSAGE_REACTIONS.bits).isEqualTo(1L shl 10)
        assertThat(GatewayIntents.DIRECT_MESSAGE.bits).isEqualTo(1L shl 12)
        assertThat(GatewayIntents.GROUP_MEMBERS.bits).isEqualTo(1L shl 24)
        assertThat(GatewayIntents.GROUP_AND_C2C_EVENT.bits).isEqualTo(1L shl 25)
        assertThat(GatewayIntents.INTERACTION.bits).isEqualTo(1L shl 26)
        assertThat(GatewayIntents.MESSAGE_AUDIT.bits).isEqualTo(1L shl 27)
        assertThat(GatewayIntents.FORUMS_EVENT.bits).isEqualTo(1L shl 28)
        assertThat(GatewayIntents.AUDIO_ACTION.bits).isEqualTo(1L shl 29)
        assertThat(GatewayIntents.PUBLIC_GUILD_MESSAGES.bits).isEqualTo(1L shl 30)
        assertThat(GatewayIntents.DEFAULT_MESSAGE_EVENTS).isSameAs(GatewayIntents.GROUP_AND_C2C_EVENT)
    }

    @Test
    fun representsNoIntentsWithCanonicalInstance() {
        assertThat(GatewayIntents.of(0L)).isSameAs(GatewayIntents.NONE)
        assertThat(GatewayIntents.NONE.isEmpty()).isTrue()
    }

    @Test
    fun combinesRemovesAndChecksIntentMasks() {
        val messages = GatewayIntents.of(1L shl 9)
        val interactions = GatewayIntents.of(1L shl 26)
        val combined = messages.union(interactions)

        assertThat(combined.bits).isEqualTo((1L shl 9) or (1L shl 26))
        assertThat(combined.containsAll(messages)).isTrue()
        assertThat(combined.containsAll(interactions)).isTrue()
        assertThat(messages.containsAll(interactions)).isFalse()
        assertThat(combined.without(messages)).isEqualTo(interactions)
    }

    @Test
    fun preservesUnknownNonNegativeBits() {
        val intents = GatewayIntents.of(1L shl 60)
        assertThat(intents.bits).isEqualTo(1L shl 60)
    }

    @Test
    fun rejectsNegativeMask() {
        assertThatIllegalArgumentException().isThrownBy { GatewayIntents(-1L) }
    }
}
