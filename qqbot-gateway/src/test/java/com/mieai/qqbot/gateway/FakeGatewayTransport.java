package com.mieai.qqbot.gateway;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class FakeGatewayTransport implements GatewayTransport {
    private final List<URI> connectedUrls = new ArrayList<>();
    private final List<FakeConnection> connections = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();

    @Override
    public CompletionStage<GatewayConnection> connect(URI gatewayUrl, Listener listener) {
        connectedUrls.add(gatewayUrl);
        FakeConnection connection = new FakeConnection();
        connections.add(connection);
        listeners.add(listener);
        return CompletableFuture.completedFuture(connection);
    }

    void emit(String payload) {
        latestListener().onText(payload);
    }

    void closeFromServer(int statusCode) {
        latestListener().onClosed(statusCode, "test close");
    }

    void fail(Throwable cause) {
        latestListener().onFailure(cause);
    }

    List<URI> connectedUrls() {
        return List.copyOf(connectedUrls);
    }

    List<FakeConnection> connections() {
        return List.copyOf(connections);
    }

    FakeConnection latestConnection() {
        return connections.getLast();
    }

    private Listener latestListener() {
        return listeners.getLast();
    }

    static final class FakeConnection implements GatewayConnection {
        private final List<String> sentPayloads = new ArrayList<>();
        private int closeCount;

        @Override
        public CompletionStage<Void> sendText(String payload) {
            sentPayloads.add(payload);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> close() {
            closeCount++;
            return CompletableFuture.completedFuture(null);
        }

        List<String> sentPayloads() {
            return List.copyOf(sentPayloads);
        }

        String latestPayload() {
            return sentPayloads.getLast();
        }

        int closeCount() {
            return closeCount;
        }
    }
}
