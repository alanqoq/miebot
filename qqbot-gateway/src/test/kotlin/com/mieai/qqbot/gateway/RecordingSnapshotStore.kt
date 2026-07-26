package com.mieai.qqbot.gateway

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class RecordingSnapshotStore(
    private val initial: GatewaySessionSnapshot? = null,
) : GatewaySnapshotStore {
    private val savesValue = mutableListOf<GatewaySessionSnapshot>()
    private var clearsValue = 0

    override fun load(): CompletionStage<GatewaySessionSnapshot?> =
        CompletableFuture.completedFuture(initial)

    override fun save(snapshot: GatewaySessionSnapshot): CompletionStage<Void> {
        savesValue += snapshot
        return CompletableFuture.completedFuture(null)
    }

    override fun clear(): CompletionStage<Void> {
        clearsValue++
        return CompletableFuture.completedFuture(null)
    }

    fun saves(): List<GatewaySessionSnapshot> = savesValue.toList()

    fun clears(): Int = clearsValue
}
