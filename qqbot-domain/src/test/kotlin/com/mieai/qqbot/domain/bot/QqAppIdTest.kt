package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class QqAppIdTest {
    @Test
    fun preservesValidAppId() {
        val appId = QqAppId.of("1029384756")
        assertThat(appId.value).isEqualTo("1029384756")
        assertThat(appId.toString()).isEqualTo("1029384756")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " 1029384756", "1029384756 ", "1029 384756", "1029\t384756"])
    fun rejectsBlankOrWhitespaceContainingAppId(value: String) {
        assertThatIllegalArgumentException().isThrownBy { QqAppId(value) }
    }

    @Test
    fun rejectsControlCharacters() {
        assertThatIllegalArgumentException()
            .isThrownBy { QqAppId("1029${Character.toString(0)}384756") }
            .withMessageContaining("control characters")
    }
}
