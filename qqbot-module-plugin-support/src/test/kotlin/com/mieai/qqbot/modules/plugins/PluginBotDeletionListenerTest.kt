package com.mieai.qqbot.modules.plugins

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.plugin.host.PluginRuntimeService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PluginBotDeletionListenerTest {
    private val runtime = mock(PluginRuntimeService::class.java)
    private val deletion = mock(PluginBotDeletionCoordinator::class.java)
    private val listener = PluginSupportModuleConfiguration().pluginBotDeletionListener(runtime, deletion)
    private val botId = BotId.parse("550e8400-e29b-41d4-a716-446655440000")

    @Test
    fun `prepares runtime before deletion and releases it on abort`() {
        `when`(deletion.abort(botId)).thenReturn(PluginBotDeletionCoordinator.AbortResolution.RESTORED)

        listener.beforeDelete(botId)
        listener.onDeleteAborted(botId)

        val order = inOrder(runtime, deletion)
        order.verify(runtime).beforeBotDeletion(botId)
        order.verify(deletion).prepare(botId)
        order.verify(deletion).abort(botId)
        order.verify(runtime).botDeletionAborted(botId)
    }

    @Test
    fun `completes an ambiguous committed deletion instead of re-enabling the bot`() {
        `when`(deletion.abort(botId)).thenReturn(PluginBotDeletionCoordinator.AbortResolution.DELETION_COMMITTED)

        listener.onDeleteAborted(botId)

        val order = inOrder(deletion, runtime)
        order.verify(deletion).abort(botId)
        order.verify(runtime).botDeletionCompleted(botId)
    }

    @Test
    fun `deletes the prepared tombstone before completing committed deletion`() {
        listener.afterDelete(botId)

        val order = inOrder(deletion, runtime)
        order.verify(deletion).commit(botId)
        order.verify(runtime).botDeletionCompleted(botId)
    }

    @Test
    fun `reports committed cleanup failure after releasing runtime deletion state`() {
        doThrow(IllegalStateException("disk unavailable")).`when`(deletion).commit(botId)

        assertThrows<IllegalStateException> { listener.afterDelete(botId) }

        verify(runtime).botDeletionCompleted(botId)
    }
}
