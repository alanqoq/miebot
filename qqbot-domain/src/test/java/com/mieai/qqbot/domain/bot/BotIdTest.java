package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BotIdTest {
    private static final String CANONICAL_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Test
    void createsFromUuidAndUsesCanonicalStringRepresentation() {
        UUID uuid = UUID.fromString(CANONICAL_ID);

        BotId id = BotId.of(uuid);

        assertThat(id.value()).isEqualTo(uuid);
        assertThat(id.toString()).isEqualTo(CANONICAL_ID);
    }

    @Test
    void parsesCanonicalUuidCaseInsensitively() {
        BotId id = BotId.parse(CANONICAL_ID.toUpperCase());

        assertThat(id.toString()).isEqualTo(CANONICAL_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "not-a-uuid", "1-1-1-1-1", " 550e8400-e29b-41d4-a716-446655440000"})
    void rejectsInvalidText(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> BotId.parse(value));
    }

    @Test
    void rejectsNullAndNilUuid() {
        assertThatNullPointerException().isThrownBy(() -> new BotId(null));
        assertThatNullPointerException().isThrownBy(() -> BotId.parse(null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BotId(new UUID(0L, 0L)))
                .withMessageContaining("nil UUID");
    }
}
