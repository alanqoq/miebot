package com.mieai.qqbot.gateway

import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

internal class FakeGatewayTransport : GatewayTransport {
    private val connectedUrlsValue = mutableListOf<URI>()
    private val connectionsValue = mutableListOf<FakeConnection>()
    private val listeners = mutableListOf<GatewayTransport.Listener>()

    override fun connect(gatewayUrl: URI, listener: GatewayTransport.Listener): CompletionStage<GatewayConnection> {
        connectedUrlsValue += gatewayUrl
        val connection = FakeConnection()
        connectionsValue += connection
        listeners += listener
        return CompletableFuture.completedFuture<GatewayConnection>(connection)
    }

    fun emit(payload: String) = listeners.last().onText(payload)

    fun closeFromServer(statusCode: Int) = listeners.last().onClosed(statusCode, "test close")

    fun fail(cause: Throwable) = listeners.last().onFailure(cause)

    fun connectedUrls(): List<URI> = connectedUrlsValue.toList()

    fun connections(): List<FakeConnection> = connectionsValue.toList()

    fun latestConnection(): FakeConnection = connectionsValue.last()

    internal class FakeConnection : GatewayConnection {
        private val sentPayloadsValue = mutableListOf<String>()
        private var closeCountValue = 0

        override fun sendText(payload: String): CompletionStage<Void> {
            sentPayloadsValue += payload
            return CompletableFuture.completedFuture(null)
        }

        override fun close(): CompletionStage<Void> {
            closeCountValue++
            return CompletableFuture.completedFuture(null)
        }

        fun sentPayloads(): List<String> = sentPayloadsValue.toList()

        fun latestPayload(): String = sentPayloadsValue.last()

        fun closeCount(): Int = closeCountValue
    }
}
