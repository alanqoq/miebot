package com.mieai.qqbot.app.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BotSupervisorLifecycleTest {

    @Test
    void enabledLifecycleStartsOnceAndSynchronousStopClosesSupervisor() {
        BotSupervisor supervisor = mock(BotSupervisor.class);
        when(supervisor.start()).thenReturn(CompletableFuture.completedFuture(null));
        BotSupervisorLifecycle lifecycle = new BotSupervisorLifecycle(supervisor, true);

        lifecycle.start();
        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1_000);
        verify(supervisor, times(1)).start();

        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        verify(supervisor).close();
    }

    @Test
    void disabledLifecycleNeverReportsRunningOrTouchesSupervisor() {
        BotSupervisor supervisor = mock(BotSupervisor.class);
        BotSupervisorLifecycle lifecycle = new BotSupervisorLifecycle(supervisor, false);
        AtomicBoolean callbackInvoked = new AtomicBoolean();

        lifecycle.start();
        lifecycle.stop(() -> callbackInvoked.set(true));

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(callbackInvoked).isTrue();
        verifyNoInteractions(supervisor);
    }

    @Test
    void asynchronousStopInvokesCallbackOnlyAfterSupervisorShutdown() {
        BotSupervisor supervisor = mock(BotSupervisor.class);
        CompletableFuture<Void> shutdown = new CompletableFuture<>();
        when(supervisor.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(supervisor.shutdown()).thenReturn(shutdown);
        BotSupervisorLifecycle lifecycle = new BotSupervisorLifecycle(supervisor, true);
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        lifecycle.start();

        lifecycle.stop(() -> callbackInvoked.set(true));

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(callbackInvoked).isFalse();
        verify(supervisor).shutdown();
        verify(supervisor, never()).close();

        shutdown.complete(null);
        assertThat(callbackInvoked).isTrue();
    }

    @Test
    void synchronousStartFailureRestoresLifecycleState() {
        BotSupervisor supervisor = mock(BotSupervisor.class);
        when(supervisor.start()).thenThrow(new IllegalStateException("supervisor unavailable"));
        BotSupervisorLifecycle lifecycle = new BotSupervisorLifecycle(supervisor, true);

        assertThatThrownBy(lifecycle::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("supervisor unavailable");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void synchronousShutdownFailureStillReleasesSpringLifecycleCallback() {
        BotSupervisor supervisor = mock(BotSupervisor.class);
        when(supervisor.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(supervisor.shutdown()).thenThrow(new IllegalStateException("shutdown unavailable"));
        BotSupervisorLifecycle lifecycle = new BotSupervisorLifecycle(supervisor, true);
        AtomicBoolean callbackInvoked = new AtomicBoolean();
        lifecycle.start();

        lifecycle.stop(() -> callbackInvoked.set(true));

        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(callbackInvoked).isTrue();
    }
}
