package com.mieai.qqbot.plugin.host

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PluginConfigurationCodecTest {
    private val codec = PluginConfigurationCodec()

    @Test
    fun parsesJsonAndYamlObjectsWithoutChangingTheirSourceText() {
        val yaml = "# retained comment\nenabled: true\nmessage: hello\n"
        val document = PluginConfigurationDocument(yaml, "config.yml")
        val parsed = codec.parseObject(document.content, document.format)

        assertThat(parsed.path("enabled").booleanValue()).isTrue()
        assertThat(parsed.path("message").textValue()).isEqualTo("hello")
        assertThat(document.content).isEqualTo(yaml)
        assertThat(PluginConfigurationDescriptor.fromDefaultResource("defaults/plugin.yaml").fileName)
            .isEqualTo("config.yaml")
        assertThat(codec.parseObject("{\"enabled\":true}", PluginConfigurationFormat.JSON).path("enabled").asBoolean())
            .isTrue()
    }

    @Test
    fun rejectsDuplicateKeysAndMultipleYamlDocuments() {
        assertThatThrownBy {
            codec.parseObject("enabled: true\nenabled: false\n", PluginConfigurationFormat.YAML)
        }.isInstanceOf(Exception::class.java)
        assertThatThrownBy {
            codec.parseObject("enabled: true\n---\nenabled: false\n", PluginConfigurationFormat.YAML)
        }.isInstanceOf(Exception::class.java)
    }
}
