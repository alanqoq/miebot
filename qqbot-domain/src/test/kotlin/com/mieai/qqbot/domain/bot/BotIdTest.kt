package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

class BotIdTest {
    @Test
    fun createsFromUuidAndUsesCanonicalStringRepresentation() {
        val uuid = UUID.fromString(CANONICAL_ID)
        val id = BotId.of(uuid)

        assertThat(id.value).isEqualTo(uuid)
        assertThat(id.toString()).isEqualTo(CANONICAL_ID)
    }

    @Test
    fun parsesCanonicalUuidCaseInsensitively() {
        val id = BotId.parse(CANONICAL_ID.uppercase())
        assertThat(id.toString()).isEqualTo(CANONICAL_ID)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "not-a-uuid", "1-1-1-1-1", " 550e8400-e29b-41d4-a716-446655440000"])
    fun rejectsInvalidText(value: String) {
        assertThatIllegalArgumentException().isThrownBy { BotId.parse(value) }
    }

    @Test
    fun rejectsNilUuid() {
        assertThatIllegalArgumentException()
            .isThrownBy { BotId(UUID(0L, 0L)) }
            .withMessageContaining("nil UUID")
    }

    companion object {
        private const val CANONICAL_ID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
