package com.mieai.qqbot.gateway;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class RecordingGatewayListener implements GatewaySessionListener {
    private final List<GatewaySessionState> states = new ArrayList<>();
    private final List<GatewaySessionSnapshot> readySnapshots = new ArrayList<>();
    private final List<GatewayDispatch> acceptanceAttempts = new ArrayList<>();
    private final List<GatewayDispatch> dispatches = new ArrayList<>();
    private final List<Integer> unknownOpcodes = new ArrayList<>();
    private final List<Long> heartbeatSequences = new ArrayList<>();
    private final List<ScheduledReconnect> reconnects = new ArrayList<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final List<GatewayCloseDecision> stops = new ArrayList<>();
    private int heartbeatAcknowledgements;
    private boolean acceptDispatch = true;
    private RuntimeException dispatchAcceptanceFailure;

    @Override
    public void onStateChanged(GatewaySessionState previous, GatewaySessionState current) {
        states.add(current);
    }

    @Override
    public void onReady(GatewaySessionSnapshot snapshot) {
        readySnapshots.add(snapshot);
    }

    @Override
    public boolean acceptDispatch(GatewayDispatch dispatch) {
        acceptanceAttempts.add(dispatch);
        if (dispatchAcceptanceFailure != null) {
            throw dispatchAcceptanceFailure;
        }
        return acceptDispatch;
    }

    @Override
    public void onDispatch(GatewayDispatch dispatch) {
        dispatches.add(dispatch);
    }

    @Override
    public void onUnknownOpcode(int opcode, String rawPayload) {
        unknownOpcodes.add(opcode);
    }

    @Override
    public void onHeartbeatSent(Long sequence) {
        heartbeatSequences.add(sequence);
    }

    @Override
    public void onHeartbeatAcknowledged() {
        heartbeatAcknowledgements++;
    }

    @Override
    public void onReconnectScheduled(
            int attempt, GatewayReconnectCause cause, Duration delay) {
        reconnects.add(new ScheduledReconnect(attempt, cause, delay));
    }

    @Override
    public void onFailure(Throwable cause) {
        failures.add(cause);
    }

    @Override
    public void onStopped(GatewayCloseDecision decision) {
        stops.add(decision);
    }

    List<GatewaySessionState> states() {
        return List.copyOf(states);
    }

    List<GatewaySessionSnapshot> readySnapshots() {
        return List.copyOf(readySnapshots);
    }

    List<GatewayDispatch> dispatches() {
        return List.copyOf(dispatches);
    }

    List<GatewayDispatch> acceptanceAttempts() {
        return List.copyOf(acceptanceAttempts);
    }

    void rejectDispatches() {
        acceptDispatch = false;
    }

    void failDispatchAcceptance() {
        dispatchAcceptanceFailure = new IllegalStateException("inbox unavailable");
    }

    List<Integer> unknownOpcodes() {
        return List.copyOf(unknownOpcodes);
    }

    List<Long> heartbeatSequences() {
        return new ArrayList<>(heartbeatSequences);
    }

    int heartbeatAcknowledgements() {
        return heartbeatAcknowledgements;
    }

    List<ScheduledReconnect> reconnects() {
        return List.copyOf(reconnects);
    }

    List<Throwable> failures() {
        return List.copyOf(failures);
    }

    List<GatewayCloseDecision> stops() {
        return List.copyOf(stops);
    }

    record ScheduledReconnect(
            int attempt, GatewayReconnectCause cause, Duration delay) {}
}
