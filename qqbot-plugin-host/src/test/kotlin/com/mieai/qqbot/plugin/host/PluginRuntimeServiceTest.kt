package com.mieai.qqbot.plugin.host

import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.inbox.EventInboxRepository
import com.mieai.qqbot.persistence.inbox.InboxEvent
import com.mieai.qqbot.persistence.inbox.InboxStatus
import com.mieai.qqbot.persistence.lease.BotLeaseRepository
import com.mieai.qqbot.persistence.plugin.BotPluginBinding
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository
import com.mieai.qqbot.persistence.plugin.PluginBindingRuntimeState
import com.mieai.qqbot.persistence.plugin.PluginDelivery
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository
import com.mieai.qqbot.persistence.plugin.PluginDeliveryStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.InOrder
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@ExtendWith(MockitoExtension::class)
class PluginRuntimeServiceTest {
    @Mock
    lateinit var host: Pf4jPluginHost

    @Mock
    lateinit var inbox: EventInboxRepository

    @Mock
    lateinit var bindings: BotPluginBindingRepository

    @Mock
    lateinit var deliveries: PluginDeliveryRepository

    @Mock
    lateinit var botLeases: BotLeaseRepository

    @Mock
    lateinit var scheduler: ScheduledExecutorService

    @Mock
    lateinit var polling: ScheduledFuture<*>

    @Test
    fun `quarantines and pauses a binding when cancellation grace expires`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        val delivery = delivery()
        val event = event()
        val cancelled = AtomicBoolean()
        val execution = object : PluginExecution {
            private val pending = CompletableFuture<Void>()

            override fun stage() = pending

            override fun cancel() {
                cancelled.set(true)
            }

            override fun isDone() = false

            override fun await(timeout: Duration) = false
        }
        `when`(inbox.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
        `when`(deliveries.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO)))
            .thenReturn(delivery)
            .thenReturn(null)
        `when`(bindings.findById(BINDING_ID)).thenReturn(binding)
        `when`(inbox.findById(EVENT_ID)).thenReturn(event)
        `when`(host.executeCancellable(binding, event, "handler")).thenReturn(execution)
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            task.value.run()
        }

        assertThat(cancelled).isTrue()
        verify(host).quarantine(BINDING_ID)
        verify(bindings).setRuntimeState(
            eqValue(BINDING_ID),
            eqValue(PluginBindingRuntimeState.QUARANTINED),
            anyString(),
            eqValue(NOW),
        )
        verify(deliveries).pauseForBinding(eqValue(BINDING_ID), eqValue(NOW), anyString())
        verify(deliveries, never()).markRetry(
            anyValue(UUID.randomUUID()),
            anyLong(),
            anyValue(NOW),
            anyValue(NOW),
            anyString(),
        )
    }

    @Test
    fun `binding change pauses and resumes durable deliveries`() {
        val runtime = service()
        `when`(bindings.findById(BINDING_ID))
            .thenReturn(binding(false, PluginBindingRuntimeState.PAUSED))
            .thenReturn(binding(true, PluginBindingRuntimeState.ACTIVE))

        runtime.bindingChanged(BINDING_ID)
        runtime.bindingChanged(BINDING_ID)

        verify(host, times(2)).invalidate(BINDING_ID)
        verify(deliveries).pauseForBinding(BINDING_ID, NOW, "Plugin binding is paused")
        verify(deliveries).resumeForBinding(BINDING_ID, NOW)
    }

    @Test
    fun `reconciles authoritative bindings before materializing or delivering`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        `when`(bindings.findAll()).thenReturn(listOf(binding))
        `when`(inbox.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
        `when`(deliveries.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            task.value.run()
        }

        val order: InOrder = inOrder(bindings, host, inbox, deliveries)
        order.verify(bindings).findAll()
        order.verify(host).reconcileBindings(listOf(binding))
        order.verify(inbox).claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))
        order.verify(deliveries).claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))
    }

    @Test
    fun `excludes bindings after lease loss and checks each bot only once`() {
        val first = binding(true, PluginBindingRuntimeState.ACTIVE)
        val second = BotPluginBinding(
            UUID.randomUUID(),
            "example",
            BOT_ID,
            true,
            1,
            NOW,
            NOW,
            PluginBindingRuntimeState.ACTIVE,
            null,
        )
        `when`(bindings.findAll()).thenReturn(listOf(first, second))
        `when`(botLeases.isOwned(BOT_ID, "instance-a", NOW)).thenReturn(false)
        `when`(
            inbox.claimNextOwned(anyString(), eqValue("instance-a"), eqValue(NOW), anyValue(Duration.ZERO)),
        ).thenReturn(null)
        `when`(
            deliveries.claimNextOwned(anyString(), eqValue("instance-a"), eqValue(NOW), anyValue(Duration.ZERO)),
        ).thenReturn(null)
        val task = scheduledTask()

        service(botLeases, "instance-a").use { runtime ->
            runtime.start()
            task.value.run()
        }

        verify(botLeases).isOwned(BOT_ID, "instance-a", NOW)
        verify(host).reconcileBindings(emptyList())
    }

    @Test
    fun `invalidates all and skips work when binding or lease reconciliation fails`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        `when`(bindings.findAll())
            .thenReturn(listOf(binding))
            .thenThrow(IllegalStateException("database unavailable"))
        `when`(botLeases.isOwned(BOT_ID, "instance-a", NOW))
            .thenThrow(IllegalStateException("lease unavailable"))
        val task = scheduledTask()

        service(botLeases, "instance-a").use { runtime ->
            runtime.start()
            task.value.run()
            task.value.run()
        }

        verify(host, times(2)).invalidateAll()
        verify(host, never()).reconcileBindings(anyValue(emptyList()))
        verify(inbox, never()).claimNextOwned(
            anyString(),
            anyString(),
            anyValue(NOW),
            anyValue(Duration.ZERO),
        )
        verify(deliveries, never()).claimNextOwned(
            anyString(),
            anyString(),
            anyValue(NOW),
            anyValue(Duration.ZERO),
        )
    }

    @Test
    fun `keeps bot blocked between successful deletion preparation and outcome`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        prepareEmptyPoll(listOf(binding))
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            runtime.beforeBotDeletion(BOT_ID)
            task.value.run()
            runtime.botDeletionAborted(BOT_ID)
            task.value.run()
        }

        verify(host).invalidateBot(BOT_ID)
        val reconciliations = bindingListCaptor()
        verify(host, times(2)).reconcileBindings(reconciliations.capture() ?: emptyList())
        assertThat(reconciliations.allValues).containsExactly(emptyList(), listOf(binding))
    }

    @Test
    fun `quiescence failure remains fail closed after delete abort`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        doThrow(IllegalStateException("callback still running")).`when`(host).invalidateBot(BOT_ID)
        prepareEmptyPoll(listOf(binding))
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            assertThatThrownBy { runtime.beforeBotDeletion(BOT_ID) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("callback still running")
            runtime.botDeletionAborted(BOT_ID)
            task.value.run()
        }

        verify(host).reconcileBindings(emptyList())
    }

    @Test
    fun `keeps binding blocked between successful mutation preparation and outcome`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        prepareEmptyPoll(listOf(binding))
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            runtime.beforeBindingMutation(BINDING_ID)
            task.value.run()
            runtime.bindingMutationCompleted(BINDING_ID)
            task.value.run()
        }

        verify(host).quiesceBindingStrict(BINDING_ID)
        val reconciliations = bindingListCaptor()
        verify(host, times(2)).reconcileBindings(reconciliations.capture() ?: emptyList())
        assertThat(reconciliations.allValues).containsExactly(emptyList(), listOf(binding))
    }

    @Test
    fun `binding quiescence failure remains fail closed after mutation abort`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        doThrow(IllegalStateException("callback still running"))
            .`when`(host).quiesceBindingStrict(BINDING_ID)
        prepareEmptyPoll(listOf(binding))
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            assertThatThrownBy { runtime.beforeBindingMutation(BINDING_ID) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage("callback still running")
            runtime.bindingMutationAborted(BINDING_ID)
            task.value.run()
        }

        verify(host).reconcileBindings(emptyList())
    }

    @Test
    fun `does not materialize a claimed event while bot deletion is prepared`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        val event = event()
        `when`(bindings.findAll()).thenReturn(listOf(binding))
        `when`(inbox.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO)))
            .thenReturn(event)
            .thenReturn(null)
        `when`(deliveries.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
        val task = scheduledTask()

        service().use { runtime ->
            runtime.start()
            runtime.beforeBotDeletion(BOT_ID)
            task.value.run()
            runtime.botDeletionCompleted(BOT_ID)
        }

        verify(inbox).markRetry(
            EVENT_ID,
            1,
            NOW,
            NOW.plusMillis(1),
            "Bot deletion is in progress on this instance",
        )
        verify(bindings, never()).findByBotId(BOT_ID)
        verify(host, never()).handlerIds(anyValue(binding), anyString())
    }

    @Test
    fun `refuses a claimed delivery when the bot lease was lost`() {
        val binding = binding(true, PluginBindingRuntimeState.ACTIVE)
        val delivery = delivery()
        `when`(bindings.findAll()).thenReturn(listOf(binding))
        `when`(botLeases.isOwned(BOT_ID, "instance-a", NOW)).thenReturn(true).thenReturn(false)
        `when`(
            inbox.claimNextOwned(anyString(), eqValue("instance-a"), eqValue(NOW), anyValue(Duration.ZERO)),
        ).thenReturn(null)
        `when`(
            deliveries.claimNextOwned(anyString(), eqValue("instance-a"), eqValue(NOW), anyValue(Duration.ZERO)),
        ).thenReturn(delivery)
        `when`(bindings.findById(BINDING_ID)).thenReturn(binding)
        val task = scheduledTask()

        service(botLeases, "instance-a").use { runtime ->
            runtime.start()
            task.value.run()
        }

        verify(host, never()).executeCancellable(anyValue(binding), anyValue(event()), anyString())
        verify(deliveries).markRetry(
            eqValue(DELIVERY_ID),
            anyLong(),
            eqValue(NOW),
            anyValue(NOW),
            anyString(),
        )
    }

    private fun scheduledTask(): ArgumentCaptor<Runnable> {
        val task = ArgumentCaptor.forClass(Runnable::class.java)
        `when`(
            scheduler.scheduleWithFixedDelay(task.capture(), eqValue(0L), eqValue(1L), eqValue(TimeUnit.MILLISECONDS)),
        ).thenAnswer { polling }
        return task
    }

    private fun prepareEmptyPoll(currentBindings: List<BotPluginBinding>) {
        `when`(bindings.findAll()).thenReturn(currentBindings)
        `when`(inbox.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
        `when`(deliveries.claimNext(anyString(), eqValue(NOW), anyValue(Duration.ZERO))).thenReturn(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bindingListCaptor(): ArgumentCaptor<List<BotPluginBinding>> =
        ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<BotPluginBinding>>

    private fun service(): PluginRuntimeService = service(null, null)

    private fun service(leases: BotLeaseRepository?, instanceId: String?) = PluginRuntimeService(
        host,
        inbox,
        bindings,
        deliveries,
        scheduler,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofMillis(1),
        Duration.ofSeconds(30),
        Duration.ofMillis(1),
        Duration.ofMillis(1),
        3,
        1,
        leases,
        instanceId,
    )

    private fun <T : Any> anyValue(fallback: T): T = ArgumentMatchers.any<T>() ?: fallback

    private fun <T : Any> eqValue(value: T): T = ArgumentMatchers.eq(value) ?: value

    private fun anyString(): String = ArgumentMatchers.anyString()

    private fun anyLong(): Long = ArgumentMatchers.anyLong()

    companion object {
        private val NOW: Instant = Instant.parse("2026-07-20T12:00:00Z")
        private val BOT_ID: BotId = BotId.parse("550e8400-e29b-41d4-a716-446655440000")
        private val BINDING_ID: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
        private val EVENT_ID: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
        private val DELIVERY_ID: UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")

        private fun binding(enabled: Boolean, state: PluginBindingRuntimeState) = BotPluginBinding(
            BINDING_ID,
            "example",
            BOT_ID,
            enabled,
            1,
            NOW,
            NOW,
            state,
            null,
        )

        private fun delivery() = PluginDelivery(
            DELIVERY_ID,
            EVENT_ID,
            BINDING_ID,
            "handler",
            PluginDeliveryStatus.IN_PROGRESS,
            1,
            NOW,
            "worker",
            NOW.plusSeconds(30),
            1,
            null,
            NOW,
            NOW,
            null,
        )

        private fun event() = InboxEvent(
            EVENT_ID,
            BotEnvironment.SANDBOX,
            BOT_ID,
            "C2C_MESSAGE_CREATE",
            "platform-event",
            "{}",
            InboxStatus.DISPATCHED,
            1,
            NOW,
            null,
            null,
            1,
            null,
            NOW,
            NOW,
        )
    }
}
