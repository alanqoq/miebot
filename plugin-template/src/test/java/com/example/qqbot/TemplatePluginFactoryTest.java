package com.example.qqbot;

import com.mieai.qqbot.plugin.spi.BotPlugin;
import com.mieai.qqbot.plugin.testkit.PluginTestContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemplatePluginFactoryTest {
    @Test
    void startsItsNamedHandlersWithTheSdkTestkit() {
        try (PluginTestContext fixture = new PluginTestContext("template", "{}")) {
            BotPlugin plugin = new TemplatePluginFactory().create(fixture.context());
            plugin.start(fixture.context());

            assertEquals(Set.of("commands", "audit"), fixture.events().handlerIds());

            plugin.stop();
        }
    }
}
