package com.mieai.qqbot.plugin.host

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PluginApiCompatibilityTest {
    @Test
    fun acceptsOlderMinorVersionsWithinTheCurrentMajor() {
        assertThat(PluginApiCompatibility.accepts("3.0.0", "3.2.0")).isTrue()
        assertThat(PluginApiCompatibility.accepts("3.1.0", "3.2.0")).isTrue()
        assertThat(PluginApiCompatibility.accepts("3.2.0", "3.2.0")).isTrue()
    }

    @Test
    fun rejectsFutureOrDifferentMajorVersions() {
        assertThat(PluginApiCompatibility.accepts("3.3.0", "3.2.0")).isFalse()
        assertThat(PluginApiCompatibility.accepts("2.9.9", "3.2.0")).isFalse()
        assertThat(PluginApiCompatibility.accepts("4.0.0", "3.2.0")).isFalse()
    }

    @Test
    fun rejectsNonCanonicalVersions() {
        assertThatThrownBy { PluginApiCompatibility.accepts("3.2", "3.2.0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
