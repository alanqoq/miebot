package com.mieai.qqbot.modules.plugins;

import com.mieai.qqbot.modules.runtime.RobotPluginArtifactHashes;
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant;
import com.mieai.qqbot.plugin.host.Pf4jPluginHost;
import com.mieai.qqbot.plugin.host.PluginRuntimeService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ConditionalOnProperty(name = "qqbot.modules.available.plugin-support", havingValue = "true")
@ComponentScan(basePackages = {
        "com.mieai.qqbot.admin.plugins",
        "com.mieai.qqbot.app.plugin"
})
public class PluginSupportModuleConfiguration {
    @Bean
    RobotPluginArtifactHashes robotPluginArtifactHashes(Pf4jPluginHost pluginHost) {
        return pluginHost::loadedPluginHashes;
    }

    @Bean
    DatabaseTransitionParticipant pluginDatabaseTransition(PluginRuntimeService runtime) {
        return new DatabaseTransitionParticipant() {
            @Override public int beforeOrder() { return 100; }
            @Override public int afterOrder() { return 200; }
            @Override public void beforeDatabaseChange() { runtime.beforeActiveDatabaseChange(); }
            @Override public void afterDatabaseChange() { runtime.activeDatabaseChanged(); }
        };
    }
}
