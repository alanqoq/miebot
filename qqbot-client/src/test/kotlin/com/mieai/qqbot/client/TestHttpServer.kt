package com.mieai.qqbot.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal class TestHttpServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    init {
        server.executor = executor
        server.start()
    }

    fun handle(path: String, handler: HttpHandler) {
        server.createContext(path, handler)
    }

    fun uri(path: String): URI = URI.create("http://127.0.0.1:${server.address.port}$path")

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun readBody(exchange: HttpExchange): String =
            String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)

        fun respond(exchange: HttpExchange, status: Int, body: String) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.use {
                it.responseBody.use { output -> output.write(bytes) }
            }
        }
    }
}
