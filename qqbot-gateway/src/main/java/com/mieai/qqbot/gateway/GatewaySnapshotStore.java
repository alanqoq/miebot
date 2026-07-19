package com.mieai.qqbot.gateway;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Persistence boundary for the resumable Gateway session state. */
public interface GatewaySnapshotStore {
    CompletionStage<Optional<GatewaySessionSnapshot>> load();

    CompletionStage<Void> save(GatewaySessionSnapshot snapshot);

    CompletionStage<Void> clear();

    static GatewaySnapshotStore none() {
        return new GatewaySnapshotStore() {
            @Override
            public CompletionStage<Optional<GatewaySessionSnapshot>> load() {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletionStage<Void> save(GatewaySessionSnapshot snapshot) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> clear() {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
