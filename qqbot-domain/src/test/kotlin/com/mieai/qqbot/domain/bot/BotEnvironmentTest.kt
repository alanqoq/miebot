package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class BotEnvironmentTest {
    @Test
    fun parsesConfigurationValueWithoutLocaleOrCaseSensitivity() {
        assertThat(BotEnvironment.parse(" sandbox ")).isEqualTo(BotEnvironment.SANDBOX)
        assertThat(BotEnvironment.parse("production")).isEqualTo(BotEnvironment.PRODUCTION)
    }

    @Test
    fun reportsWhetherEnvironmentIsSandbox() {
        assertThat(BotEnvironment.SANDBOX.isSandbox()).isTrue()
        assertThat(BotEnvironment.PRODUCTION.isSandbox()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "staging"])
    fun rejectsUnsupportedValues(value: String) {
        assertThatIllegalArgumentException().isThrownBy { BotEnvironment.parse(value) }
    }
}
