package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BotRevisionTest {
    @Test
    fun startsAtOneAndIncrementsMonotonically() {
        val initial = BotRevision.initial()

        assertThat(initial.value).isEqualTo(1L)
        assertThat(BotRevision.of(1L)).isSameAs(initial)
        assertThat(initial.next()).isEqualTo(BotRevision.of(2L))
        assertThat(initial.compareTo(initial.next())).isNegative()
    }

    @ParameterizedTest
    @ValueSource(longs = [Long.MIN_VALUE, -1L, 0L])
    fun rejectsNonPositiveRevision(value: Long) {
        assertThatIllegalArgumentException().isThrownBy { BotRevision(value) }
    }

    @Test
    fun rejectsRevisionOverflow() {
        val maximum = BotRevision.of(Long.MAX_VALUE)
        assertThatExceptionOfType(ArithmeticException::class.java).isThrownBy { maximum.next() }
    }
}
