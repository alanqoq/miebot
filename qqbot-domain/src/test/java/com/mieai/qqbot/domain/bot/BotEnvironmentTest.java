package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BotEnvironmentTest {
    @Test
    void parsesConfigurationValueWithoutLocaleOrCaseSensitivity() {
        assertThat(BotEnvironment.parse(" sandbox ")).isEqualTo(BotEnvironment.SANDBOX);
        assertThat(BotEnvironment.parse("production")).isEqualTo(BotEnvironment.PRODUCTION);
    }

    @Test
    void reportsWhetherEnvironmentIsSandbox() {
        assertThat(BotEnvironment.SANDBOX.isSandbox()).isTrue();
        assertThat(BotEnvironment.PRODUCTION.isSandbox()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "staging"})
    void rejectsUnsupportedValues(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> BotEnvironment.parse(value));
    }

    @Test
    void rejectsNullValue() {
        assertThatNullPointerException().isThrownBy(() -> BotEnvironment.parse(null));
    }
}
