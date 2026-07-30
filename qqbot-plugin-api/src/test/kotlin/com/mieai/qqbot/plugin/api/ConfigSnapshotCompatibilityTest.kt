package com.mieai.qqbot.plugin.api

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ConfigSnapshotCompatibilityTest {
    @Test
    fun retainsApi31ConstructorAndAccessors() {
        val constructor = ConfigSnapshot::class.java.getConstructor(
            String::class.java,
            Long::class.javaPrimitiveType,
            Instant::class.java,
        )
        val snapshot = constructor.newInstance("{\"enabled\":true}", 3L, LOADED_AT)

        assertThat(snapshot.json).isEqualTo("{\"enabled\":true}")
        assertThat(snapshot.content).isEqualTo(snapshot.json)
        assertThat(snapshot.fileName).isEqualTo("config.json")
    }

    @Test
    fun retainsApi31CopyDescriptorsAndPreservesTheConfigurationFileName() {
        val source = ConfigSnapshot("enabled: true\n", 3L, LOADED_AT, "config.yml")
        val copy = ConfigSnapshot::class.java.getDeclaredMethod(
            "copy",
            String::class.java,
            Long::class.javaPrimitiveType,
            Instant::class.java,
        )
        val copied = copy.invoke(source, "enabled: false\n", 4L, LOADED_AT.plusSeconds(1)) as ConfigSnapshot

        assertThat(copied.content).isEqualTo("enabled: false\n")
        assertThat(copied.fileName).isEqualTo("config.yml")

        val copyDefault = ConfigSnapshot::class.java.getDeclaredMethod(
            "copy\$default",
            ConfigSnapshot::class.java,
            String::class.java,
            Long::class.javaPrimitiveType,
            Instant::class.java,
            Int::class.javaPrimitiveType,
            Any::class.java,
        )
        val defaulted = copyDefault.invoke(null, source, null, 0L, null, 0x01 or 0x02 or 0x04, null) as ConfigSnapshot

        assertThat(defaulted).isEqualTo(source)
        assertThat(defaulted.fileName).isEqualTo("config.yml")
    }

    private companion object {
        val LOADED_AT: Instant = Instant.parse("2026-07-30T00:00:00Z")
    }
}
