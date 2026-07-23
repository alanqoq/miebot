package com.mieai.qqbot.admin.plugins

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.BotRepository
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginArtifact
import com.mieai.qqbot.persistence.plugin.PluginArtifactRepository
import com.mieai.qqbot.plugin.host.Pf4jPluginHost
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DuplicateKeyException
import java.util.Optional

class PluginBindingAdministrationServiceTest {
    private val bindings = mock(BotPluginBindingRepository::class.java)
    private val artifacts = mock(PluginArtifactRepository::class.java)
    private val bots = mock(BotRepository::class.java)
    private val host = mock(Pf4jPluginHost::class.java)
    private val runtime = mock(PluginRuntimeService::class.java)
    private val files = mock(PluginBindingFileService::class.java)
    private val service = PluginBindingAdministrationService(bindings, artifacts, bots, host, runtime, files)

    @Test
    fun `duplicate create loser never initializes or deletes winner directory`() {
        val botId = BotId.parse("550e8400-e29b-41d4-a716-446655440001")
        val request = CreatePluginBindingRequest("echo", botId.toString(), "{}", true)
        `when`(bots.findById(botId)).thenReturn(Optional.of(mock(StoredBot::class.java)))
        `when`(artifacts.findById("echo")).thenReturn(Optional.of(mock(PluginArtifact::class.java)))
        `when`(host.isLoaded("echo")).thenReturn(true)
        `when`(bindings.findByPluginAndBot("echo", botId)).thenReturn(Optional.empty())
        `when`(files.normalizedConfiguration("echo", "{}")).thenReturn("{}")
        doThrow(DuplicateKeyException("duplicate")).`when`(bindings).insert(any(BotPluginBinding::class.java))

        val failure = assertThrows<PluginAdministrationException> { service.create(request) }

        assertThat(failure.code()).isEqualTo("BINDING_EXISTS")
        verify(files).normalizedConfiguration("echo", "{}")
        verifyNoMoreInteractions(files)
    }
}
