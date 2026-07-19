package com.mieai.qqbot.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class RecordingSnapshotStore implements GatewaySnapshotStore {
    private final Optional<GatewaySessionSnapshot> initial;
    private final List<GatewaySessionSnapshot> saves = new ArrayList<>();
    private int clears;

    RecordingSnapshotStore() {
        this(Optional.empty());
    }

    RecordingSnapshotStore(GatewaySessionSnapshot initial) {
        this(Optional.of(initial));
    }

    private RecordingSnapshotStore(Optional<GatewaySessionSnapshot> initial) {
        this.initial = initial;
    }

    @Override
    public CompletionStage<Optional<GatewaySessionSnapshot>> load() {
        return CompletableFuture.completedFuture(initial);
    }

    @Override
    public CompletionStage<Void> save(GatewaySessionSnapshot snapshot) {
        saves.add(snapshot);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> clear() {
        clears++;
        return CompletableFuture.completedFuture(null);
    }

    List<GatewaySessionSnapshot> saves() {
        return List.copyOf(saves);
    }

    int clears() {
        return clears;
    }
}
