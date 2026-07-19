package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QqAppIdTest {
    @Test
    void preservesValidAppId() {
        QqAppId appId = QqAppId.of("1029384756");

        assertThat(appId.value()).isEqualTo("1029384756");
        assertThat(appId.toString()).isEqualTo("1029384756");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1029384756", "1029384756 ", "1029 384756", "1029\t384756"})
    void rejectsBlankOrWhitespaceContainingAppId(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new QqAppId(value));
    }

    @Test
    void rejectsNullAndControlCharacters() {
        assertThatNullPointerException().isThrownBy(() -> new QqAppId(null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new QqAppId("1029" + Character.toString(0) + "384756"))
                .withMessageContaining("control characters");
    }
}
