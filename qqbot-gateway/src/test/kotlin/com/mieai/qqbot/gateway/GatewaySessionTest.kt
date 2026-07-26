package com.mieai.qqbot.gateway

import com.mieai.qqbot.client.AccessToken
import com.mieai.qqbot.client.TokenProvider
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.protocol.gateway.GatewayIdentify
import com.mieai.qqbot.protocol.gateway.GatewayOpcode
import com.mieai.qqbot.protocol.gateway.GatewayResume
import com.mieai.qqbot.protocol.json.JsonCodecs
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GatewaySessionTest {
    @Test
    fun identifiesBecomesReadyPersistsSequenceAndMaintainsHeartbeat() {
        val fixture = fixture(RecordingSnapshotStore())
        fixture.session.start().toCompletableFuture().join()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.AWAITING_HELLO)
        assertThat(fixture.transport.connectedUrls()).singleElement().isSameAs(GATEWAY_URL)
        fixture.transport.emit(hello())

        val identify = JSON.decodeGatewayEnvelope(
            fixture.transport.latestConnection().latestPayload(), GatewayIdentify::class.java,
        )
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.IDENTIFYING)
        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY)
        assertThat(identify.data!!.token).isEqualTo("QQBot gateway-token")
        assertThat(identify.data!!.intents).isEqualTo((1L shl 25) or (1L shl 26))
        assertThat(identify.data!!.shard).containsExactly(1, 4)

        fixture.transport.emit(ready(1L))
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 1L))
        assertThat(fixture.store!!.saves()).contains(GatewaySessionSnapshot("session-a", 1L))
        assertThat(fixture.listener.readySnapshots()).containsExactly(GatewaySessionSnapshot("session-a", 1L))

        val dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-1\",\"d\":{\"id\":\"message-1\",\"future\":true},\"future\":true}"
        fixture.transport.emit(dispatch)
        fixture.transport.emit("{\"op\":99,\"s\":3,\"d\":{\"future\":true}}")

        assertThat(fixture.listener.dispatches()).containsExactly(
            GatewayDispatch(2L, "C2C_MESSAGE_CREATE", dispatch, "event-1", "session-a"),
        )
        assertThat(fixture.listener.dispatches().first().platformEventId).isEqualTo("event-1")
        assertThat(fixture.listener.unknownOpcodes()).containsExactly(99)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 3L))

        fixture.transport.emit("{\"op\":1}")
        val requestedHeartbeat = JSON.decodeGatewayEnvelope(
            fixture.transport.latestConnection().latestPayload(), Long::class.java,
        )
        assertThat(requestedHeartbeat.opcode()).isEqualTo(GatewayOpcode.HEARTBEAT)
        assertThat(requestedHeartbeat.data).isEqualTo(3L)
        fixture.transport.emit("{\"op\":11}")

        fixture.scheduler.advance(Duration.ofSeconds(45))
        val scheduledHeartbeat = JSON.decodeGatewayEnvelope(
            fixture.transport.latestConnection().latestPayload(), Long::class.java,
        )
        assertThat(scheduledHeartbeat.opcode()).isEqualTo(GatewayOpcode.HEARTBEAT)
        assertThat(scheduledHeartbeat.data).isEqualTo(3L)
        fixture.transport.emit("{\"op\":11,\"future\":true}")
        fixture.scheduler.advance(Duration.ofSeconds(5))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY)
        assertThat(fixture.listener.heartbeatSequences()).containsExactly(3L, 3L)
        assertThat(fixture.listener.heartbeatAcknowledgements()).isEqualTo(2)
        assertThat(fixture.listener.failures()).isEmpty()
        assertThat(fixture.backoff.causes()).isEmpty()
    }

    @Test
    fun acceptsDispatchBeforeAdvancingSnapshotAndPrefersTopLevelEventId() {
        val fixture = readyFixture()
        val dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"gateway-event-id\",\"d\":{\"id\":\"message-id\"}}"

        fixture.transport.emit(dispatch)

        val accepted = fixture.listener.acceptanceAttempts().single()
        assertThat(accepted.platformEventId).isEqualTo("gateway-event-id")
        assertThat(accepted.eventType).isEqualTo("C2C_MESSAGE_CREATE")
        assertThat(accepted.rawPayload).isEqualTo(dispatch)
        assertThat(fixture.listener.dispatches()).containsExactlyElementsOf(fixture.listener.acceptanceAttempts())
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 2L))
        assertThat(fixture.store!!.saves()).contains(GatewaySessionSnapshot("session-a", 2L))
    }

    @Test
    fun extractsNestedMessageIdWhenGatewayEnvelopeHasNoEventId() {
        val fixture = readyFixture()
        fixture.transport.emit("{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{\"id\":\"message-id\",\"content\":\"hello\"}}")

        assertThat(fixture.listener.acceptanceAttempts()).singleElement()
            .extracting(GatewayDispatch::platformEventId).isEqualTo("message-id")
    }

    @Test
    fun doesNotTreatAResourceIdAsTheEventIdForNonMessageUpdates() {
        val raw = "{\"op\":0,\"s\":2,\"t\":\"GUILD_UPDATE\",\"d\":{\"id\":\"guild-id\",\"name\":\"renamed\"}}"
        assertThat(GatewayDispatch(2L, "GUILD_UPDATE", raw).platformEventId).isNull()
    }

    @Test
    fun rejectedDispatchDoesNotAdvanceOrNotifyAndReconnectsFromPreviousSnapshot() {
        val fixture = readyFixture()
        fixture.listener.rejectDispatches()
        fixture.transport.emit("{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-rejected\",\"d\":{}}")

        assertThat(fixture.listener.acceptanceAttempts()).hasSize(1)
        assertThat(fixture.listener.dispatches()).isEmpty()
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 1L))
        assertDispatchRejected(fixture)
    }

    @Test
    fun dispatchAcceptanceExceptionUsesTheSameRecoverableReconnectPath() {
        val fixture = readyFixture()
        fixture.listener.failDispatchAcceptance()
        fixture.transport.emit("{\"op\":0,\"s\":2,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"id\":\"event-failed\",\"d\":{}}")

        assertThat(fixture.listener.dispatches()).isEmpty()
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 1L))
        assertDispatchRejected(fixture)
    }

    @Test
    fun resumesSavedSessionAndReconnectsWithInjectedBackoff() {
        val fixture = fixture(RecordingSnapshotStore(GatewaySessionSnapshot("saved-session", 42L)))
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())

        val firstResume = JSON.decodeGatewayEnvelope(fixture.transport.latestConnection().latestPayload(), GatewayResume::class.java)
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.RESUMING)
        assertThat(firstResume.data!!.token).isEqualTo("QQBot gateway-token")
        assertThat(firstResume.data!!.sessionId).isEqualTo("saved-session")
        assertThat(firstResume.data!!.sequence).isEqualTo(42L)

        fixture.transport.emit("{\"op\":0,\"s\":43,\"t\":\"RESUMED\",\"d\":\"\"}")
        fixture.transport.emit("{\"op\":7}")

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.backoff.attempts()).containsExactly(1)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.SERVER_RECONNECT)
        assertThat(fixture.listener.reconnects()).containsExactly(
            RecordingGatewayListener.ScheduledReconnect(1, GatewayReconnectCause.SERVER_RECONNECT, Duration.ofSeconds(3)),
        )
        assertThat(fixture.transport.connections().first().closeCount()).isEqualTo(1)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("saved-session", 43L))

        fixture.scheduler.advance(Duration.ofSeconds(3))
        assertThat(fixture.transport.connections()).hasSize(2)
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.AWAITING_HELLO)
        fixture.transport.emit(hello())
        val secondResume = JSON.decodeGatewayEnvelope(fixture.transport.latestConnection().latestPayload(), GatewayResume::class.java)
        assertThat(secondResume.data!!.sessionId).isEqualTo("saved-session")
        assertThat(secondResume.data!!.sequence).isEqualTo(43L)
    }

    @Test
    fun clearsRejectedSessionAndIdentifiesAfterInvalidSession() {
        val store = RecordingSnapshotStore(GatewaySessionSnapshot("rejected-session", 9L))
        val fixture = fixture(store)
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        fixture.transport.emit("{\"op\":9,\"d\":false}")

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isNull()
        assertThat(store.clears()).isEqualTo(1)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.INVALID_SESSION)

        fixture.scheduler.advance(Duration.ofSeconds(3))
        fixture.transport.emit(hello())
        val identify = JSON.decodeGatewayEnvelope(fixture.transport.latestConnection().latestPayload(), GatewayIdentify::class.java)
        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY)
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.IDENTIFYING)
    }

    @Test
    fun reconnectsWhenHeartbeatAckDeadlineExpires() {
        val fixture = readyFixture()
        fixture.scheduler.advance(Duration.ofSeconds(45))
        fixture.scheduler.advance(Duration.ofSeconds(5))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.ACK_TIMEOUT)
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 1L))
    }

    @Test
    fun respondsToServerHeartbeatWhileAwaitingAckAndRefreshesDeadline() {
        val fixture = readyFixture()
        fixture.transport.emit("{\"op\":1}")
        fixture.scheduler.advance(Duration.ofSeconds(4))
        fixture.transport.emit("{\"op\":1}")
        fixture.scheduler.advance(Duration.ofSeconds(1))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY)
        assertThat(fixture.listener.heartbeatSequences()).containsExactly(1L, 1L)
        assertThat(fixture.backoff.causes()).isEmpty()
        fixture.transport.emit("{\"op\":11}")
        fixture.scheduler.advance(Duration.ofSeconds(5))
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY)
        assertThat(fixture.listener.heartbeatAcknowledgements()).isEqualTo(1)
    }

    @Test
    fun reconnectsNormalServerCloseAndResumesSavedSession() {
        val fixture = fixture(RecordingSnapshotStore(GatewaySessionSnapshot("normal-close-session", 17L)))
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.closeFromServer(1000)

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("normal-close-session", 17L))
        assertThat(fixture.listener.stops()).isEmpty()
        fixture.scheduler.advance(Duration.ofSeconds(3))
        fixture.transport.emit(hello())
        val resume = JSON.decodeGatewayEnvelope(fixture.transport.latestConnection().latestPayload(), GatewayResume::class.java)
        assertThat(resume.opcode()).isEqualTo(GatewayOpcode.RESUME)
        assertThat(resume.data!!.sessionId).isEqualTo("normal-close-session")
    }

    @Test
    fun invalidatesRejectedTokenAndIdentifiesAfterAuthenticationClose() {
        val store = RecordingSnapshotStore(GatewaySessionSnapshot("rejected-token-session", 21L))
        val fixture = fixture(store)
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        fixture.transport.closeFromServer(4004)

        assertThat(fixture.tokenProvider.invalidatedTokens()).containsExactly(fixture.tokenProvider.token())
        assertThat(fixture.session.snapshot()).isNull()
        assertThat(store.clears()).isEqualTo(1)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.AUTHENTICATION_FAILURE)
        fixture.scheduler.advance(Duration.ofSeconds(3))
        fixture.transport.emit(hello())
        val identify = JSON.decodeGatewayEnvelope(fixture.transport.latestConnection().latestPayload(), GatewayIdentify::class.java)
        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY)
    }

    @Test
    fun reconnectsWhenHelloDoesNotArriveBeforeDeadline() {
        val fixture = fixture(RecordingSnapshotStore())
        fixture.session.start().toCompletableFuture().join()
        fixture.scheduler.advance(Duration.ofSeconds(10))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE)
        val failure = fixture.listener.failures().single()
        assertThat(failure).isInstanceOf(GatewaySessionException::class.java)
        assertThat(failure.cause).isInstanceOf(TimeoutException::class.java)
    }

    @Test
    fun reconnectsWithIdentifyWhenReadyDoesNotArriveBeforeDeadline() {
        val fixture = fixture(RecordingSnapshotStore())
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        fixture.scheduler.advance(Duration.ofSeconds(15))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isNull()
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE)
    }

    @Test
    fun preservesSnapshotWhenResumedDoesNotArriveBeforeDeadline() {
        val snapshot = GatewaySessionSnapshot("resume-timeout", 33L)
        val fixture = fixture(RecordingSnapshotStore(snapshot))
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        fixture.scheduler.advance(Duration.ofSeconds(15))

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isEqualTo(snapshot)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE)
    }

    @Test
    fun reconnectsOnRegressedDispatchSequenceWithoutDeliveringEvent() {
        val fixture = readyFixture(5L)
        assertThatCode { fixture.transport.emit("{\"op\":0,\"s\":4,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{}}") }
            .doesNotThrowAnyException()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isEqualTo(GatewaySessionSnapshot("session-a", 5L))
        assertThat(fixture.listener.dispatches()).isEmpty()
        assertProtocolFailure(fixture.listener)
    }

    @Test
    fun reconnectsOnRegressedResumedSequence() {
        val snapshot = GatewaySessionSnapshot("resume-regression", 42L)
        val fixture = fixture(RecordingSnapshotStore(snapshot))
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        assertThatCode { fixture.transport.emit("{\"op\":0,\"s\":41,\"t\":\"RESUMED\",\"d\":\"\"}") }
            .doesNotThrowAnyException()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.session.snapshot()).isEqualTo(snapshot)
        assertThat(fixture.listener.readySnapshots()).isEmpty()
        assertProtocolFailure(fixture.listener)
    }

    @Test
    fun reconnectsOnRegressedSequenceAttachedToNonDispatchOpcode() {
        val fixture = readyFixture(5L)
        assertThatCode { fixture.transport.emit("{\"op\":99,\"s\":4,\"d\":{}}") }.doesNotThrowAnyException()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.listener.unknownOpcodes()).isEmpty()
        assertProtocolFailure(fixture.listener)
    }

    @Test
    fun failsInitialStartCleanlyWhenSchedulerRejectsHelloTimeout() {
        val fixture = fixture(RecordingSnapshotStore())
        fixture.scheduler.failNextSchedule()
        assertThatThrownBy { fixture.session.start().toCompletableFuture().join() }
            .hasRootCauseMessage("Unable to schedule the Gateway Hello timeout")

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED)
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1)
        assertTransportFailure(fixture.listener)
    }

    @Test
    fun stopsWithoutLeakingCallbackExceptionWhenSchedulerRejectsAckDeadline() {
        val fixture = readyFixture()
        fixture.scheduler.failNextSchedule()
        assertThatCode { fixture.transport.emit("{\"op\":1}") }.doesNotThrowAnyException()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED)
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1)
        assertTransportFailure(fixture.listener)
    }

    @Test
    fun stopsWithoutLeakingCallbackExceptionWhenSchedulerRejectsReconnect() {
        val fixture = readyFixture()
        fixture.scheduler.failNextSchedule()
        assertThatCode { fixture.transport.emit("{\"op\":7}") }.doesNotThrowAnyException()

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED)
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1)
        assertTransportFailure(fixture.listener)
    }

    @Test
    fun stopsOnNonRetryableCloseAndReportsDecision() {
        val fixture = fixture(RecordingSnapshotStore())
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.closeFromServer(4014)

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED)
        val decision = fixture.listener.stops().single()
        assertThat(decision.statusCode).isEqualTo(4014)
        assertThat(decision.disposition).isEqualTo(GatewayCloseDisposition.STOP)
        assertThat(fixture.backoff.causes()).isEmpty()
    }

    @Test
    fun serializesSnapshotSaveBeforeClearAndWaitsForPersistenceOnStop() {
        val store = DeferredSnapshotStore()
        val fixture = fixture(store)
        fixture.session.start().toCompletableFuture().join()
        fixture.transport.emit(hello())
        fixture.transport.emit(ready(1L))
        fixture.transport.emit("{\"op\":9,\"d\":false}")
        val stopping = fixture.session.stop()

        assertThat(store.clearCalled).isFalse()
        assertThat(stopping.toCompletableFuture()).isNotDone()
        store.pendingSave.complete(null)
        assertThat(store.clearCalled).isTrue()
        assertThat(stopping.toCompletableFuture()).isCompleted()
    }

    private fun readyFixture(sequence: Long = 1L): Fixture = fixture(RecordingSnapshotStore()).also {
        it.session.start().toCompletableFuture().join()
        it.transport.emit(hello())
        it.transport.emit(ready(sequence))
    }

    private fun fixture(store: RecordingSnapshotStore): Fixture = fixture(store, store)

    private fun fixture(store: GatewaySnapshotStore): Fixture = fixture(store, null)

    private fun fixture(snapshotStore: GatewaySnapshotStore, recordingStore: RecordingSnapshotStore?): Fixture {
        val transport = FakeGatewayTransport()
        val scheduler = ManualGatewayScheduler()
        val listener = RecordingGatewayListener()
        val backoff = RecordingBackoffStrategy(Duration.ofSeconds(3))
        val config = GatewaySessionConfig(
            GATEWAY_URL,
            GatewayIntents.of((1L shl 25) or (1L shl 26)),
            ShardSpec(1, 4),
            Duration.ofSeconds(5),
            mapOf("\$os" to "linux", "\$browser" to "gateway-test", "\$device" to "gateway-test"),
        )
        val tokenProvider = RecordingTokenProvider()
        return Fixture(
            GatewaySession(config, tokenProvider, transport, snapshotStore, backoff, scheduler, listener),
            transport, scheduler, recordingStore, backoff, listener, tokenProvider,
        )
    }

    private fun hello() = "{\"op\":10,\"d\":{\"heartbeat_interval\":45000,\"future\":true},\"future\":true}"

    private fun ready(sequence: Long) = "{\"op\":0,\"s\":$sequence,\"t\":\"READY\",\"d\":{\"version\":1,\"session_id\":\"session-a\",\"user\":{\"id\":\"6158788878435714165\",\"username\":\"Gateway Bot\",\"avatar\":\"\",\"bot\":true},\"shard\":[1,4],\"future\":true},\"future\":true}"

    private fun assertProtocolFailure(listener: RecordingGatewayListener) {
        val failure = listener.failures().single()
        assertThat(failure).isInstanceOf(GatewaySessionException::class.java)
        assertThat((failure as GatewaySessionException).category()).isEqualTo(GatewayReconnectCause.PROTOCOL_FAILURE)
    }

    private fun assertTransportFailure(listener: RecordingGatewayListener) {
        val failure = listener.failures().single()
        assertThat(failure).isInstanceOf(GatewaySessionException::class.java)
        assertThat((failure as GatewaySessionException).category()).isEqualTo(GatewayReconnectCause.TRANSPORT_FAILURE)
    }

    private fun assertDispatchRejected(fixture: Fixture) {
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF)
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.DISPATCH_REJECTED)
        val reconnect = fixture.listener.reconnects().single()
        assertThat(reconnect.cause).isEqualTo(GatewayReconnectCause.DISPATCH_REJECTED)
        val failure = fixture.listener.failures().single()
        assertThat(failure).isInstanceOf(GatewaySessionException::class.java)
        assertThat((failure as GatewaySessionException).category()).isEqualTo(GatewayReconnectCause.DISPATCH_REJECTED)
    }

    private data class Fixture(
        val session: GatewaySession,
        val transport: FakeGatewayTransport,
        val scheduler: ManualGatewayScheduler,
        val store: RecordingSnapshotStore?,
        val backoff: RecordingBackoffStrategy,
        val listener: RecordingGatewayListener,
        val tokenProvider: RecordingTokenProvider,
    )

    private class RecordingTokenProvider : TokenProvider {
        private val tokenValue = AccessToken.of("gateway-token", Instant.parse("2026-07-16T14:00:00Z"))
        private val invalidatedTokensValue = mutableListOf<AccessToken>()

        override fun getAccessToken(): CompletionStage<AccessToken> = CompletableFuture.completedFuture(tokenValue)
        override fun invalidate(rejectedToken: AccessToken) { invalidatedTokensValue += rejectedToken }
        fun token(): AccessToken = tokenValue
        fun invalidatedTokens(): List<AccessToken> = invalidatedTokensValue.toList()
    }

    private class DeferredSnapshotStore : GatewaySnapshotStore {
        val pendingSave = CompletableFuture<Void>()
        var clearCalled = false

        override fun load(): CompletionStage<GatewaySessionSnapshot?> = CompletableFuture.completedFuture(null)
        override fun save(snapshot: GatewaySessionSnapshot): CompletionStage<Void> = pendingSave
        override fun clear(): CompletionStage<Void> {
            clearCalled = true
            return CompletableFuture.completedFuture(null)
        }
    }

    private companion object {
        val GATEWAY_URL: URI = URI.create("wss://gateway.example/websocket/?v=1&encoding=json")
        val JSON = JsonCodecs.defaultCodec()
    }
}
