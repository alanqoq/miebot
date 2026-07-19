package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class GatewayIntentsTest {
    @Test
    void exposesTheCurrentOfficialIntentBitsAndSafeDefault() {
        assertThat(GatewayIntents.GUILDS.bits()).isEqualTo(1L << 0);
        assertThat(GatewayIntents.GUILD_MEMBERS.bits()).isEqualTo(1L << 1);
        assertThat(GatewayIntents.GUILD_MESSAGES.bits()).isEqualTo(1L << 9);
        assertThat(GatewayIntents.GUILD_MESSAGE_REACTIONS.bits()).isEqualTo(1L << 10);
        assertThat(GatewayIntents.DIRECT_MESSAGE.bits()).isEqualTo(1L << 12);
        assertThat(GatewayIntents.GROUP_AND_C2C_EVENT.bits()).isEqualTo(1L << 25);
        assertThat(GatewayIntents.INTERACTION.bits()).isEqualTo(1L << 26);
        assertThat(GatewayIntents.MESSAGE_AUDIT.bits()).isEqualTo(1L << 27);
        assertThat(GatewayIntents.FORUMS_EVENT.bits()).isEqualTo(1L << 28);
        assertThat(GatewayIntents.AUDIO_ACTION.bits()).isEqualTo(1L << 29);
        assertThat(GatewayIntents.PUBLIC_GUILD_MESSAGES.bits()).isEqualTo(1L << 30);
        assertThat(GatewayIntents.DEFAULT_MESSAGE_EVENTS)
                .isSameAs(GatewayIntents.GROUP_AND_C2C_EVENT);
    }

    @Test
    void representsNoIntentsWithCanonicalInstance() {
        assertThat(GatewayIntents.of(0L)).isSameAs(GatewayIntents.NONE);
        assertThat(GatewayIntents.NONE.isEmpty()).isTrue();
    }

    @Test
    void combinesRemovesAndChecksIntentMasks() {
        GatewayIntents messages = GatewayIntents.of(1L << 9);
        GatewayIntents interactions = GatewayIntents.of(1L << 26);

        GatewayIntents combined = messages.union(interactions);

        assertThat(combined.bits()).isEqualTo((1L << 9) | (1L << 26));
        assertThat(combined.containsAll(messages)).isTrue();
        assertThat(combined.containsAll(interactions)).isTrue();
        assertThat(messages.containsAll(interactions)).isFalse();
        assertThat(combined.without(messages)).isEqualTo(interactions);
    }

    @Test
    void preservesUnknownNonNegativeBits() {
        GatewayIntents intents = GatewayIntents.of(1L << 60);

        assertThat(intents.bits()).isEqualTo(1L << 60);
    }

    @Test
    void rejectsNegativeMaskAndNullOperands() {
        assertThatIllegalArgumentException().isThrownBy(() -> new GatewayIntents(-1L));
        assertThatNullPointerException().isThrownBy(() -> GatewayIntents.NONE.containsAll(null));
        assertThatNullPointerException().isThrownBy(() -> GatewayIntents.NONE.union(null));
        assertThatNullPointerException().isThrownBy(() -> GatewayIntents.NONE.without(null));
    }
}
