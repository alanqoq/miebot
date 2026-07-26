package com.mieai.qqbot.runtime.configuration

import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotConfigurationChangeListenerTest {
    @Test
    fun `composite forwards deletion preparation commit and abort hooks`() {
        val first = RecordingListener()
        val second = RecordingListener()
        val composite = BotConfigurationChangeListener.composite(listOf(first, second))
        val deleted = BotConfigurationChange(BOT_ID, BotRevision.initial(), false, BotConfigurationChangeKind.DELETED)

        composite.beforeDelete(BOT_ID)
        composite.onDeleteAborted(BOT_ID)
        composite.afterDelete(BOT_ID)
        composite.onCommitted(deleted)

        assertThat(first.beforeDeleteCalls).isEqualTo(1)
        assertThat(second.beforeDeleteCalls).isEqualTo(1)
        assertThat(first.abortCalls).isEqualTo(1)
        assertThat(second.abortCalls).isEqualTo(1)
        assertThat(first.afterDeleteCalls).isEqualTo(1)
        assertThat(second.afterDeleteCalls).isEqualTo(1)
        assertThat(first.committed).containsExactly(deleted)
        assertThat(second.committed).containsExactly(deleted)
    }

    @Test
    fun `composite still invokes later participants when one preparation fails`() {
        val first = RecordingListener().apply { beforeDeleteFailure = IllegalStateException("first unavailable") }
        val second = RecordingListener()
        val composite = BotConfigurationChangeListener.composite(listOf(first, second))

        assertThatThrownBy { composite.beforeDelete(BOT_ID) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("first unavailable")

        assertThat(second.beforeDeleteCalls).isEqualTo(1)
    }

    private class RecordingListener : BotConfigurationChangeListener {
        var beforeDeleteCalls = 0
        var abortCalls = 0
        var afterDeleteCalls = 0
        val committed = ArrayList<BotConfigurationChange>()
        var beforeDeleteFailure: RuntimeException? = null

        override fun beforeDelete(botId: BotId) {
            beforeDeleteCalls++
            beforeDeleteFailure?.let { throw it }
        }

        override fun onDeleteAborted(botId: BotId) {
            abortCalls++
        }

        override fun afterDelete(botId: BotId) {
            afterDeleteCalls++
        }

        override fun onCommitted(change: BotConfigurationChange) {
            committed += change
        }
    }

    private companion object {
        val BOT_ID: BotId = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
    }
}
