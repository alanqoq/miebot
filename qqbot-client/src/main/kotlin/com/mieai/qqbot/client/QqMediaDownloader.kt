package com.mieai.qqbot.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** Bounded, redirect-aware downloader used before handing media to QQ. */
class QqMediaDownloader(
    private val maxBytes: Long,
    private val timeout: Duration,
    private val maxRedirects: Int,
) {
    private val client: HttpClient

    init {
        require(maxBytes >= 1L) { "maxBytes must be positive" }
        require(!timeout.isZero && !timeout.isNegative) { "timeout must be positive" }
        require(maxRedirects in 0..8) { "maxRedirects is invalid" }
        client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun download(source: URI): CompletionStage<ByteArray> =
        CompletableFuture.supplyAsync { downloadSync(source) }

    private fun downloadSync(source: URI): ByteArray {
        var current = validate(source)
        var redirect = 0
        while (true) {
            val request = HttpRequest.newBuilder(current)
                .timeout(timeout)
                .header("Accept", "*/*")
                .GET()
                .build()
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                val status = response.statusCode()
                if (status in 300..399) {
                    response.body().close()
                    require(redirect < maxRedirects) { "media URL exceeded redirect limit" }
                    val location = response.headers().firstValue("location").orElseThrow {
                        IllegalArgumentException("media redirect has no Location header")
                    }
                    current = validate(current.resolve(location))
                    redirect++
                    continue
                }
                if (status !in 200..299) {
                    response.body().close()
                    throw IllegalArgumentException("media URL returned HTTP $status")
                }
                val declared = response.headers().firstValueAsLong("content-length").orElse(-1L)
                require(declared <= maxBytes) { "media download exceeds configured limit" }
                response.body().use { input ->
                    ByteArrayOutputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            total += read
                            require(total <= maxBytes) { "media download exceeds configured limit" }
                            output.write(buffer, 0, read)
                        }
                        return output.toByteArray()
                    }
                }
            } catch (exception: IOException) {
                throw IllegalStateException("Unable to download media URL", exception)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Media download was interrupted", exception)
            }
        }
    }

    companion object {
        private fun validate(value: URI): URI {
            require(value.isAbsolute && value.scheme.equals("https", ignoreCase = true) &&
                value.host != null && value.userInfo == null && value.fragment == null &&
                value.toString().length <= 2048) {
                "media URL must be an HTTPS URL without credentials or fragments"
            }
            val host = value.host
            require(!host.equals("localhost", ignoreCase = true) && !host.endsWith(".localhost")) {
                "media URL host is not public"
            }
            try {
                for (address in InetAddress.getAllByName(host)) {
                    require(!privateAddress(address)) { "media URL target is private" }
                }
            } catch (exception: UnknownHostException) {
                throw IllegalArgumentException("media URL host cannot be resolved", exception)
            }
            return value
        }

        private fun privateAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) {
                return true
            }
            if (address is Inet6Address) {
                val bytes = address.address
                return (bytes[0].toInt() and 0xfe) == 0xfc
            }
            return false
        }
    }
}
