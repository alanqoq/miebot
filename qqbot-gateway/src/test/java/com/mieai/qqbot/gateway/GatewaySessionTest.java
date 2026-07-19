package com.mieai.qqbot.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.client.AccessToken;
import com.mieai.qqbot.client.TokenProvider;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.protocol.gateway.GatewayEnvelope;
import com.mieai.qqbot.protocol.gateway.GatewayIdentify;
import com.mieai.qqbot.protocol.gateway.GatewayOpcode;
import com.mieai.qqbot.protocol.gateway.GatewayResume;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class GatewaySessionTest {
    private static final URI GATEWAY_URL =
            URI.create("wss://gateway.example/websocket/?v=1&encoding=json");
    private static final JsonCodec JSON = JsonCodecs.defaultCodec();

    @Test
    void identifiesBecomesReadyPersistsSequenceAndMaintainsHeartbeat() {
        Fixture fixture = fixture(new RecordingSnapshotStore());

        fixture.session.start().toCompletableFuture().join();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.AWAITING_HELLO);
        assertThat(fixture.transport.connectedUrls()).singleElement().isSameAs(GATEWAY_URL);

        fixture.transport.emit(hello());

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.IDENTIFYING);
        GatewayEnvelope<GatewayIdentify> identify = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayIdentify.class);
        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY);
        assertThat(identify.data().token()).isEqualTo("QQBot gateway-token");
        assertThat(identify.data().intents()).isEqualTo((1L << 25) | (1L << 26));
        assertThat(identify.data().shard()).containsExactly(1, 4);

        fixture.transport.emit(ready(1L));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY);
        assertThat(fixture.session.snapshot()).contains(new GatewaySessionSnapshot("session-a", 1L));
        assertThat(fixture.store.saves()).contains(new GatewaySessionSnapshot("session-a", 1L));
        assertThat(fixture.listener.readySnapshots()).containsExactly(
                new GatewaySessionSnapshot("session-a", 1L));

        String dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"id\":\"event-1\",\"d\":{\"id\":\"message-1\",\"future\":true},\"future\":true}";
        fixture.transport.emit(dispatch);
        fixture.transport.emit("{\"op\":99,\"s\":3,\"d\":{\"future\":true}}");

        assertThat(fixture.listener.dispatches())
                .containsExactly(new GatewayDispatch(
                        2L, "C2C_MESSAGE_CREATE", dispatch, "event-1", "session-a"));
        assertThat(fixture.listener.dispatches().getFirst().platformEventId()).isEqualTo("event-1");
        assertThat(fixture.listener.unknownOpcodes()).containsExactly(99);
        assertThat(fixture.session.snapshot()).contains(new GatewaySessionSnapshot("session-a", 3L));

        fixture.transport.emit("{\"op\":1}");
        GatewayEnvelope<Long> requestedHeartbeat = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), Long.class);
        assertThat(requestedHeartbeat.opcode()).isEqualTo(GatewayOpcode.HEARTBEAT);
        assertThat(requestedHeartbeat.data()).isEqualTo(3L);
        fixture.transport.emit("{\"op\":11}");

        fixture.scheduler.advance(Duration.ofSeconds(45));
        GatewayEnvelope<Long> scheduledHeartbeat = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), Long.class);
        assertThat(scheduledHeartbeat.opcode()).isEqualTo(GatewayOpcode.HEARTBEAT);
        assertThat(scheduledHeartbeat.data()).isEqualTo(3L);
        fixture.transport.emit("{\"op\":11,\"future\":true}");
        fixture.scheduler.advance(Duration.ofSeconds(5));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY);
        assertThat(fixture.listener.heartbeatSequences()).containsExactly(3L, 3L);
        assertThat(fixture.listener.heartbeatAcknowledgements()).isEqualTo(2);
        assertThat(fixture.listener.failures()).isEmpty();
        assertThat(fixture.backoff.causes()).isEmpty();
    }

    @Test
    void acceptsDispatchBeforeAdvancingSnapshotAndPrefersTopLevelEventId() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));
        String dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"id\":\"gateway-event-id\",\"d\":{\"id\":\"message-id\"}}";

        fixture.transport.emit(dispatch);

        assertThat(fixture.listener.acceptanceAttempts()).singleElement().satisfies(accepted -> {
            assertThat(accepted.platformEventId()).isEqualTo("gateway-event-id");
            assertThat(accepted.eventType()).isEqualTo("C2C_MESSAGE_CREATE");
            assertThat(accepted.rawPayload()).isEqualTo(dispatch);
        });
        assertThat(fixture.listener.dispatches())
                .containsExactlyElementsOf(fixture.listener.acceptanceAttempts());
        assertThat(fixture.session.snapshot())
                .contains(new GatewaySessionSnapshot("session-a", 2L));
        assertThat(fixture.store.saves())
                .contains(new GatewaySessionSnapshot("session-a", 2L));
    }

    @Test
    void extractsNestedMessageIdWhenGatewayEnvelopeHasNoEventId() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));

        String dispatch = "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\","
                + "\"d\":{\"id\":\"message-id\",\"content\":\"hello\"}}";
        fixture.transport.emit(dispatch);

        assertThat(fixture.listener.acceptanceAttempts()).singleElement()
                .extracting(GatewayDispatch::platformEventId)
                .isEqualTo("message-id");
    }

    @Test
    void doesNotTreatAResourceIdAsTheEventIdForNonMessageUpdates() {
        String raw = "{\"op\":0,\"s\":2,\"t\":\"GUILD_UPDATE\","
                + "\"d\":{\"id\":\"guild-id\",\"name\":\"renamed\"}}";

        GatewayDispatch dispatch = new GatewayDispatch(2L, "GUILD_UPDATE", raw);

        assertThat(dispatch.platformEventId()).isNull();
    }

    @Test
    void rejectedDispatchDoesNotAdvanceOrNotifyAndReconnectsFromPreviousSnapshot() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));
        fixture.listener.rejectDispatches();

        fixture.transport.emit(
                "{\"op\":0,\"s\":2,\"t\":\"C2C_MESSAGE_CREATE\",\"id\":\"event-rejected\",\"d\":{}}");

        assertThat(fixture.listener.acceptanceAttempts()).hasSize(1);
        assertThat(fixture.listener.dispatches()).isEmpty();
        assertThat(fixture.session.snapshot())
                .contains(new GatewaySessionSnapshot("session-a", 1L));
        assertDispatchRejected(fixture);
    }

    @Test
    void dispatchAcceptanceExceptionUsesTheSameRecoverableReconnectPath() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));
        fixture.listener.failDispatchAcceptance();

        fixture.transport.emit(
                "{\"op\":0,\"s\":2,\"t\":\"GROUP_AT_MESSAGE_CREATE\",\"id\":\"event-failed\",\"d\":{}}");

        assertThat(fixture.listener.dispatches()).isEmpty();
        assertThat(fixture.session.snapshot())
                .contains(new GatewaySessionSnapshot("session-a", 1L));
        assertDispatchRejected(fixture);
    }

    @Test
    void resumesSavedSessionAndReconnectsWithInjectedBackoff() {
        Fixture fixture = fixture(new RecordingSnapshotStore(
                new GatewaySessionSnapshot("saved-session", 42L)));
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        GatewayEnvelope<GatewayResume> firstResume = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayResume.class);
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.RESUMING);
        assertThat(firstResume.data().token()).isEqualTo("QQBot gateway-token");
        assertThat(firstResume.data().sessionId()).isEqualTo("saved-session");
        assertThat(firstResume.data().sequence()).isEqualTo(42L);

        fixture.transport.emit("{\"op\":0,\"s\":43,\"t\":\"RESUMED\",\"d\":\"\"}");
        fixture.transport.emit("{\"op\":7}");

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.backoff.attempts()).containsExactly(1);
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.SERVER_RECONNECT);
        assertThat(fixture.listener.reconnects())
                .containsExactly(new RecordingGatewayListener.ScheduledReconnect(
                        1,
                        GatewayReconnectCause.SERVER_RECONNECT,
                        Duration.ofSeconds(3)));
        assertThat(fixture.transport.connections().getFirst().closeCount()).isEqualTo(1);
        assertThat(fixture.session.snapshot()).contains(
                new GatewaySessionSnapshot("saved-session", 43L));

        fixture.scheduler.advance(Duration.ofSeconds(3));
        assertThat(fixture.transport.connections()).hasSize(2);
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.AWAITING_HELLO);
        fixture.transport.emit(hello());

        GatewayEnvelope<GatewayResume> secondResume = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayResume.class);
        assertThat(secondResume.data().sessionId()).isEqualTo("saved-session");
        assertThat(secondResume.data().sequence()).isEqualTo(43L);
    }

    @Test
    void clearsRejectedSessionAndIdentifiesAfterInvalidSession() {
        RecordingSnapshotStore store = new RecordingSnapshotStore(
                new GatewaySessionSnapshot("rejected-session", 9L));
        Fixture fixture = fixture(store);
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        fixture.transport.emit("{\"op\":9,\"d\":false}");

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot()).isEmpty();
        assertThat(store.clears()).isEqualTo(1);
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.INVALID_SESSION);

        fixture.scheduler.advance(Duration.ofSeconds(3));
        fixture.transport.emit(hello());
        GatewayEnvelope<GatewayIdentify> identify = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayIdentify.class);

        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY);
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.IDENTIFYING);
    }

    @Test
    void reconnectsWhenHeartbeatAckDeadlineExpires() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));

        fixture.scheduler.advance(Duration.ofSeconds(45));
        fixture.scheduler.advance(Duration.ofSeconds(5));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.backoff.causes()).containsExactly(GatewayReconnectCause.ACK_TIMEOUT);
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1);
        assertThat(fixture.session.snapshot()).contains(new GatewaySessionSnapshot("session-a", 1L));
    }

    @Test
    void respondsToServerHeartbeatWhileAwaitingAckAndRefreshesDeadline() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));

        fixture.transport.emit("{\"op\":1}");
        fixture.scheduler.advance(Duration.ofSeconds(4));
        fixture.transport.emit("{\"op\":1}");
        fixture.scheduler.advance(Duration.ofSeconds(1));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY);
        assertThat(fixture.listener.heartbeatSequences()).containsExactly(1L, 1L);
        assertThat(fixture.backoff.causes()).isEmpty();

        fixture.transport.emit("{\"op\":11}");
        fixture.scheduler.advance(Duration.ofSeconds(5));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.READY);
        assertThat(fixture.listener.heartbeatAcknowledgements()).isEqualTo(1);
    }

    @Test
    void reconnectsNormalServerCloseAndResumesSavedSession() {
        Fixture fixture = fixture(new RecordingSnapshotStore(
                new GatewaySessionSnapshot("normal-close-session", 17L)));
        fixture.session.start().toCompletableFuture().join();

        fixture.transport.closeFromServer(1000);

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot())
                .contains(new GatewaySessionSnapshot("normal-close-session", 17L));
        assertThat(fixture.listener.stops()).isEmpty();

        fixture.scheduler.advance(Duration.ofSeconds(3));
        fixture.transport.emit(hello());
        GatewayEnvelope<GatewayResume> resume = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayResume.class);

        assertThat(resume.opcode()).isEqualTo(GatewayOpcode.RESUME);
        assertThat(resume.data().sessionId()).isEqualTo("normal-close-session");
    }

    @Test
    void invalidatesRejectedTokenAndIdentifiesAfterAuthenticationClose() {
        RecordingSnapshotStore store = new RecordingSnapshotStore(
                new GatewaySessionSnapshot("rejected-token-session", 21L));
        Fixture fixture = fixture(store);
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        fixture.transport.closeFromServer(4004);

        assertThat(fixture.tokenProvider.invalidatedTokens())
                .containsExactly(fixture.tokenProvider.token());
        assertThat(fixture.session.snapshot()).isEmpty();
        assertThat(store.clears()).isEqualTo(1);
        assertThat(fixture.backoff.causes())
                .containsExactly(GatewayReconnectCause.AUTHENTICATION_FAILURE);

        fixture.scheduler.advance(Duration.ofSeconds(3));
        fixture.transport.emit(hello());
        GatewayEnvelope<GatewayIdentify> identify = JSON.decodeGatewayEnvelope(
                fixture.transport.latestConnection().latestPayload(), GatewayIdentify.class);

        assertThat(identify.opcode()).isEqualTo(GatewayOpcode.IDENTIFY);
    }

    @Test
    void reconnectsWhenHelloDoesNotArriveBeforeDeadline() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();

        fixture.scheduler.advance(Duration.ofSeconds(10));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.backoff.causes())
                .containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE);
        assertThat(fixture.listener.failures()).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(GatewaySessionException.class);
            assertThat(failure.getCause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        });
    }

    @Test
    void reconnectsWithIdentifyWhenReadyDoesNotArriveBeforeDeadline() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        fixture.scheduler.advance(Duration.ofSeconds(15));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot()).isEmpty();
        assertThat(fixture.backoff.causes())
                .containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE);
    }

    @Test
    void preservesSnapshotWhenResumedDoesNotArriveBeforeDeadline() {
        GatewaySessionSnapshot snapshot = new GatewaySessionSnapshot("resume-timeout", 33L);
        Fixture fixture = fixture(new RecordingSnapshotStore(snapshot));
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        fixture.scheduler.advance(Duration.ofSeconds(15));

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot()).contains(snapshot);
        assertThat(fixture.backoff.causes())
                .containsExactly(GatewayReconnectCause.PROTOCOL_FAILURE);
    }

    @Test
    void reconnectsOnRegressedDispatchSequenceWithoutDeliveringEvent() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(5L));

        assertThatCode(() -> fixture.transport.emit(
                        "{\"op\":0,\"s\":4,\"t\":\"C2C_MESSAGE_CREATE\",\"d\":{}}"))
                .doesNotThrowAnyException();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot())
                .contains(new GatewaySessionSnapshot("session-a", 5L));
        assertThat(fixture.listener.dispatches()).isEmpty();
        assertProtocolFailure(fixture.listener);
    }

    @Test
    void reconnectsOnRegressedResumedSequence() {
        GatewaySessionSnapshot snapshot = new GatewaySessionSnapshot("resume-regression", 42L);
        Fixture fixture = fixture(new RecordingSnapshotStore(snapshot));
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());

        assertThatCode(() -> fixture.transport.emit(
                        "{\"op\":0,\"s\":41,\"t\":\"RESUMED\",\"d\":\"\"}"))
                .doesNotThrowAnyException();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.session.snapshot()).contains(snapshot);
        assertThat(fixture.listener.readySnapshots()).isEmpty();
        assertProtocolFailure(fixture.listener);
    }

    @Test
    void reconnectsOnRegressedSequenceAttachedToNonDispatchOpcode() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(5L));

        assertThatCode(() -> fixture.transport.emit("{\"op\":99,\"s\":4,\"d\":{}}"))
                .doesNotThrowAnyException();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.listener.unknownOpcodes()).isEmpty();
        assertProtocolFailure(fixture.listener);
    }

    @Test
    void failsInitialStartCleanlyWhenSchedulerRejectsHelloTimeout() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.scheduler.failNextSchedule();

        assertThatThrownBy(() -> fixture.session.start().toCompletableFuture().join())
                .hasRootCauseMessage("Unable to schedule the Gateway Hello timeout");

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED);
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1);
        assertTransportFailure(fixture.listener);
    }

    @Test
    void stopsWithoutLeakingCallbackExceptionWhenSchedulerRejectsAckDeadline() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));
        fixture.scheduler.failNextSchedule();

        assertThatCode(() -> fixture.transport.emit("{\"op\":1}"))
                .doesNotThrowAnyException();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED);
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1);
        assertTransportFailure(fixture.listener);
    }

    @Test
    void stopsWithoutLeakingCallbackExceptionWhenSchedulerRejectsReconnect() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));
        fixture.scheduler.failNextSchedule();

        assertThatCode(() -> fixture.transport.emit("{\"op\":7}"))
                .doesNotThrowAnyException();

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED);
        assertThat(fixture.transport.latestConnection().closeCount()).isEqualTo(1);
        assertTransportFailure(fixture.listener);
    }

    @Test
    void stopsOnNonRetryableCloseAndReportsDecision() {
        Fixture fixture = fixture(new RecordingSnapshotStore());
        fixture.session.start().toCompletableFuture().join();

        fixture.transport.closeFromServer(4014);

        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.STOPPED);
        assertThat(fixture.listener.stops()).singleElement().satisfies(decision -> {
            assertThat(decision.statusCode()).isEqualTo(4014);
            assertThat(decision.disposition()).isEqualTo(GatewayCloseDisposition.STOP);
        });
        assertThat(fixture.backoff.causes()).isEmpty();
    }

    @Test
    void serializesSnapshotSaveBeforeClearAndWaitsForPersistenceOnStop() {
        DeferredSnapshotStore store = new DeferredSnapshotStore();
        Fixture fixture = fixture(store);
        fixture.session.start().toCompletableFuture().join();
        fixture.transport.emit(hello());
        fixture.transport.emit(ready(1L));

        fixture.transport.emit("{\"op\":9,\"d\":false}");
        CompletionStage<Void> stopping = fixture.session.stop();

        assertThat(store.clearCalled).isFalse();
        assertThat(stopping.toCompletableFuture()).isNotDone();

        store.pendingSave.complete(null);

        assertThat(store.clearCalled).isTrue();
        assertThat(stopping.toCompletableFuture()).isCompleted();
    }

    private static Fixture fixture(RecordingSnapshotStore store) {
        return fixture((GatewaySnapshotStore) store, store);
    }

    private static Fixture fixture(GatewaySnapshotStore store) {
        return fixture(store, null);
    }

    private static Fixture fixture(
            GatewaySnapshotStore snapshotStore, RecordingSnapshotStore recordingStore) {
        FakeGatewayTransport transport = new FakeGatewayTransport();
        ManualGatewayScheduler scheduler = new ManualGatewayScheduler();
        RecordingGatewayListener listener = new RecordingGatewayListener();
        RecordingBackoffStrategy backoff =
                new RecordingBackoffStrategy(Duration.ofSeconds(3));
        GatewaySessionConfig config = new GatewaySessionConfig(
                GATEWAY_URL,
                GatewayIntents.of((1L << 25) | (1L << 26)),
                new ShardSpec(1, 4),
                Duration.ofSeconds(5),
                Map.of("$os", "linux", "$browser", "gateway-test", "$device", "gateway-test"));
        RecordingTokenProvider tokenProvider = new RecordingTokenProvider();
        GatewaySession session = new GatewaySession(
                config, tokenProvider, transport, snapshotStore, backoff, scheduler, listener);
        return new Fixture(
                session,
                transport,
                scheduler,
                recordingStore,
                backoff,
                listener,
                tokenProvider);
    }

    private static String hello() {
        return "{\"op\":10,\"d\":{\"heartbeat_interval\":45000,\"future\":true},"
                + "\"future\":true}";
    }

    private static String ready(long sequence) {
        return "{\"op\":0,\"s\":" + sequence + ",\"t\":\"READY\",\"d\":{"
                + "\"version\":1,\"session_id\":\"session-a\","
                + "\"user\":{\"id\":\"6158788878435714165\","
                + "\"username\":\"Gateway Bot\",\"avatar\":\"\",\"bot\":true},"
                + "\"shard\":[1,4],\"future\":true},\"future\":true}";
    }

    private static void assertProtocolFailure(RecordingGatewayListener listener) {
        assertThat(listener.failures()).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(GatewaySessionException.class);
            assertThat(((GatewaySessionException) failure).category())
                    .isEqualTo(GatewayReconnectCause.PROTOCOL_FAILURE);
        });
    }

    private static void assertTransportFailure(RecordingGatewayListener listener) {
        assertThat(listener.failures()).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(GatewaySessionException.class);
            assertThat(((GatewaySessionException) failure).category())
                    .isEqualTo(GatewayReconnectCause.TRANSPORT_FAILURE);
        });
    }

    private static void assertDispatchRejected(Fixture fixture) {
        assertThat(fixture.session.state()).isEqualTo(GatewaySessionState.BACKING_OFF);
        assertThat(fixture.backoff.causes())
                .containsExactly(GatewayReconnectCause.DISPATCH_REJECTED);
        assertThat(fixture.listener.reconnects()).singleElement().satisfies(reconnect ->
                assertThat(reconnect.cause()).isEqualTo(GatewayReconnectCause.DISPATCH_REJECTED));
        assertThat(fixture.listener.failures()).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(GatewaySessionException.class);
            assertThat(((GatewaySessionException) failure).category())
                    .isEqualTo(GatewayReconnectCause.DISPATCH_REJECTED);
        });
    }

    private record Fixture(
            GatewaySession session,
            FakeGatewayTransport transport,
            ManualGatewayScheduler scheduler,
            RecordingSnapshotStore store,
            RecordingBackoffStrategy backoff,
            RecordingGatewayListener listener,
            RecordingTokenProvider tokenProvider) {}

    private static final class RecordingTokenProvider implements TokenProvider {
        private final AccessToken token =
                AccessToken.of("gateway-token", Instant.parse("2026-07-16T14:00:00Z"));
        private final java.util.List<AccessToken> invalidatedTokens = new java.util.ArrayList<>();

        @Override
        public CompletionStage<AccessToken> getAccessToken() {
            return CompletableFuture.completedFuture(token);
        }

        @Override
        public void invalidate(AccessToken rejectedToken) {
            invalidatedTokens.add(rejectedToken);
        }

        AccessToken token() {
            return token;
        }

        java.util.List<AccessToken> invalidatedTokens() {
            return java.util.List.copyOf(invalidatedTokens);
        }
    }

    private static final class DeferredSnapshotStore implements GatewaySnapshotStore {
        private final CompletableFuture<Void> pendingSave = new CompletableFuture<>();
        private boolean clearCalled;

        @Override
        public CompletionStage<Optional<GatewaySessionSnapshot>> load() {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<Void> save(GatewaySessionSnapshot snapshot) {
            return pendingSave;
        }

        @Override
        public CompletionStage<Void> clear() {
            clearCalled = true;
            return CompletableFuture.completedFuture(null);
        }
    }
}
