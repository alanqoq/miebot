package com.mieai.qqbot.modules.plugins

import com.mieai.qqbot.admin.plugins.PluginBindingFileService
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.modules.database.DatabaseTransitionParticipant
import com.mieai.qqbot.modules.runtime.RobotPluginArtifactHashes
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import com.mieai.qqbot.runtime.configuration.BotConfigurationChangeListener
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

@AutoConfiguration
@ConditionalOnProperty(name = ["qqbot.modules.available.plugin-support"], havingValue = "true")
@ComponentScan(
    basePackages = [
        "com.mieai.qqbot.admin.plugins",
        "com.mieai.qqbot.app.plugin",
    ],
)
class PluginSupportModuleConfiguration {
    @Bean
    fun pluginBotDeletionCoordinator(
        bots: BotRepository,
        host: Pf4jPluginHost,
    ) = PluginBotDeletionCoordinator(bots, host)

    @Bean
    fun pluginBotDeletionListener(
        runtime: PluginRuntimeService,
        deletion: PluginBotDeletionCoordinator,
    ): BotConfigurationChangeListener = object : BotConfigurationChangeListener {
        override fun beforeDelete(botId: BotId) {
            runtime.beforeBotDeletion(botId)
            deletion.prepare(botId)
        }

        override fun onDeleteAborted(botId: BotId) {
            when (deletion.abort(botId)) {
                PluginBotDeletionCoordinator.AbortResolution.RESTORED -> runtime.botDeletionAborted(botId)
                PluginBotDeletionCoordinator.AbortResolution.DELETION_COMMITTED -> runtime.botDeletionCompleted(botId)
            }
        }

        override fun afterDelete(botId: BotId) {
            try {
                deletion.commit(botId)
            } finally {
                runtime.botDeletionCompleted(botId)
            }
        }

        override fun onCommitted(change: com.mieai.qqbot.runtime.configuration.BotConfigurationChange) = Unit
    }

    @Bean
    fun robotPluginArtifactHashes(pluginHost: Pf4jPluginHost) =
        RobotPluginArtifactHashes { pluginHost.loadedPluginHashes() }

    @Bean
    fun pluginDatabaseTransition(
        runtime: PluginRuntimeService,
        files: PluginBindingFileService,
    ): DatabaseTransitionParticipant = object : DatabaseTransitionParticipant {
        override fun beforeOrder(): Int = 100

        override fun afterOrder(): Int = 200

        override fun beforeDatabaseChange() = runtime.beforeActiveDatabaseChange()

        override fun afterDatabaseChange() {
            files.initializeMissingBindings()
            runtime.activeDatabaseChanged()
        }
    }
}
