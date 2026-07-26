package com.example.qqbot

import com.mieai.qqbot.plugin.testkit.PluginTestContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplatePluginFactoryTest {
    @Test
    fun startsItsNamedHandlersWithTheSdkTestkit() {
        PluginTestContext("template", "{}").use { fixture ->
            val plugin = TemplatePluginFactory().create(fixture.context)
            plugin.start()

            assertEquals(setOf("commands", "audit"), fixture.events.handlerIds())

            plugin.stop()
        }
    }
}
