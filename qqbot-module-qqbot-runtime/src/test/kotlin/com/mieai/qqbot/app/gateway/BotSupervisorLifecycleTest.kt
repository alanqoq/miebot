package com.mieai.qqbot.app.gateway

import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

class BotSupervisorLifecycleTest {
    @Test
    fun `enabled lifecycle starts once and synchronous stop closes supervisor`() {
        val supervisor = mock(BotSupervisor::class.java)
        `when`(supervisor.start()).thenReturn(CompletableFuture.completedFuture<Void>(null))
        val lifecycle = BotSupervisorLifecycle(supervisor, true)

        lifecycle.start()
        lifecycle.start()

        assertThat(lifecycle.isRunning).isTrue()
        assertThat(lifecycle.isAutoStartup).isTrue()
        assertThat(lifecycle.phase).isEqualTo(Int.MAX_VALUE - 1_000)
        verify(supervisor, times(1)).start()

        lifecycle.stop()

        assertThat(lifecycle.isRunning).isFalse()
        verify(supervisor).close()
    }

    @Test
    fun `disabled lifecycle never reports running or touches supervisor`() {
        val supervisor = mock(BotSupervisor::class.java)
        val lifecycle = BotSupervisorLifecycle(supervisor, false)
        val callbackInvoked = AtomicBoolean()

        lifecycle.start()
        lifecycle.stop { callbackInvoked.set(true) }

        assertThat(lifecycle.isRunning).isFalse()
        assertThat(callbackInvoked).isTrue()
        verifyNoInteractions(supervisor)
    }

    @Test
    fun `asynchronous stop invokes callback only after supervisor shutdown`() {
        val supervisor = mock(BotSupervisor::class.java)
        val shutdown = CompletableFuture<Void>()
        `when`(supervisor.start()).thenReturn(CompletableFuture.completedFuture<Void>(null))
        `when`(supervisor.shutdown()).thenReturn(shutdown)
        val lifecycle = BotSupervisorLifecycle(supervisor, true)
        val callbackInvoked = AtomicBoolean()
        lifecycle.start()

        lifecycle.stop { callbackInvoked.set(true) }

        assertThat(lifecycle.isRunning).isFalse()
        assertThat(callbackInvoked).isFalse()
        verify(supervisor).shutdown()
        verify(supervisor, never()).close()

        shutdown.complete(null)
        assertThat(callbackInvoked).isTrue()
    }

    @Test
    fun `synchronous start failure restores lifecycle state`() {
        val supervisor = mock(BotSupervisor::class.java)
        `when`(supervisor.start()).thenThrow(IllegalStateException("supervisor unavailable"))
        val lifecycle = BotSupervisorLifecycle(supervisor, true)

        assertThatThrownBy(lifecycle::start)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("supervisor unavailable")
        assertThat(lifecycle.isRunning).isFalse()
    }

    @Test
    fun `synchronous shutdown failure still releases Spring lifecycle callback`() {
        val supervisor = mock(BotSupervisor::class.java)
        `when`(supervisor.start()).thenReturn(CompletableFuture.completedFuture<Void>(null))
        `when`(supervisor.shutdown()).thenThrow(IllegalStateException("shutdown unavailable"))
        val lifecycle = BotSupervisorLifecycle(supervisor, true)
        val callbackInvoked = AtomicBoolean()
        lifecycle.start()

        lifecycle.stop { callbackInvoked.set(true) }

        assertThat(lifecycle.isRunning).isFalse()
        assertThat(callbackInvoked).isTrue()
    }
}
