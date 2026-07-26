package com.mieai.qqbot.gateway

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Persistence boundary for the resumable Gateway session state. */
interface GatewaySnapshotStore {
    fun load(): CompletionStage<GatewaySessionSnapshot?>

    fun save(snapshot: GatewaySessionSnapshot): CompletionStage<Void>

    fun clear(): CompletionStage<Void>

    companion object {
        fun none(): GatewaySnapshotStore = object : GatewaySnapshotStore {
            override fun load(): CompletionStage<GatewaySessionSnapshot?> =
                CompletableFuture.completedFuture(null)

            override fun save(snapshot: GatewaySessionSnapshot): CompletionStage<Void> =
                CompletableFuture.completedFuture(null)

            override fun clear(): CompletionStage<Void> = CompletableFuture.completedFuture(null)
        }
    }
}
