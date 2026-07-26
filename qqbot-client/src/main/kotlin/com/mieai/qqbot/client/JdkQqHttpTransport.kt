package com.mieai.qqbot.client

import com.mieai.qqbot.protocol.error.QqApiError
import com.mieai.qqbot.protocol.json.JsonCodec
import com.mieai.qqbot.protocol.json.JsonCodecException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

class JdkQqHttpTransport(
    private val jsonCodec: JsonCodec,
    private val requestTimeout: Duration,
) {
    private val httpClient: HttpClient

    init {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun <T> postJson(endpoint: URI, requestBody: Any?, responseType: Class<T>): CompletionStage<T> {
        val encoded = try {
            jsonCodec.encode(requestBody)
        } catch (exception: JsonCodecException) {
            return CompletableFuture.failedFuture(QqClientException.protocol(endpoint, exception))
        }

        val request = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", JSON)
            .header("Content-Type", JSON)
            .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
            .build()
        return send(request, responseType)
    }

    fun <T> postAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "POST",
        endpoint,
        accessToken,
        requestBody,
        emptyMap(),
        responseType,
    )

    fun <T> postAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "POST",
        endpoint,
        accessToken,
        requestBody,
        headers,
        responseType,
    )

    fun <T> putAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "PUT",
        endpoint,
        accessToken,
        requestBody,
        emptyMap(),
        responseType,
    )

    fun <T> putAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "PUT",
        endpoint,
        accessToken,
        requestBody,
        headers,
        responseType,
    )

    fun <T> patchAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "PATCH",
        endpoint,
        accessToken,
        requestBody,
        emptyMap(),
        responseType,
    )

    fun <T> patchAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "PATCH",
        endpoint,
        accessToken,
        requestBody,
        headers,
        responseType,
    )

    fun <T> deleteAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "DELETE",
        endpoint,
        accessToken,
        requestBody,
        emptyMap(),
        responseType,
    )

    fun <T> deleteAuthorizedJson(
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "DELETE",
        endpoint,
        accessToken,
        requestBody,
        headers,
        responseType,
    )

    fun <T> deleteAuthorized(
        endpoint: URI,
        accessToken: AccessToken,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "DELETE",
        endpoint,
        accessToken,
        null,
        emptyMap(),
        responseType,
    )

    fun <T> deleteAuthorized(
        endpoint: URI,
        accessToken: AccessToken,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedJson(
        "DELETE",
        endpoint,
        accessToken,
        null,
        headers,
        responseType,
    )

    /** Sends a bounded multipart request used by QQ's channel/direct image endpoint. */
    fun <T> postAuthorizedMultipart(
        endpoint: URI,
        accessToken: AccessToken,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray,
        responseType: Class<T>,
    ): CompletionStage<T> = postAuthorizedMultipart(
        endpoint,
        accessToken,
        emptyMap(),
        fields,
        fileField,
        fileName,
        contentType,
        fileBytes,
        responseType,
    )

    fun <T> postAuthorizedMultipart(
        endpoint: URI,
        accessToken: AccessToken,
        headers: Map<String, String>,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray,
        responseType: Class<T>,
    ): CompletionStage<T> {
        val boundary = "----qqbot-${UUID.randomUUID()}"
        val body = try {
            multipartBody(boundary, fields, fileField, fileName, contentType, fileBytes)
        } catch (exception: IOException) {
            return CompletableFuture.failedFuture(QqClientException.protocol(endpoint, exception))
        }
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", JSON)
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .header("Authorization", accessToken.authorizationHeaderValue())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        addHeaders(builder, headers)
        return send(builder.build(), responseType)
    }

    fun <T> getJson(
        endpoint: URI,
        accessToken: AccessToken,
        responseType: Class<T>,
    ): CompletionStage<T> = getJson(endpoint, accessToken, emptyMap(), responseType)

    fun <T> getJson(
        endpoint: URI,
        accessToken: AccessToken,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> {
        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", JSON)
            .header("Authorization", accessToken.authorizationHeaderValue())
            .GET()
        addHeaders(builder, headers)
        return send(builder.build(), responseType)
    }

    private fun <T> authorizedJson(
        method: String,
        endpoint: URI,
        accessToken: AccessToken,
        requestBody: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> {
        var publisher: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody()
        if (requestBody != null) {
            try {
                val encoded = jsonCodec.encode(requestBody)
                publisher = HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8)
            } catch (exception: JsonCodecException) {
                return CompletableFuture.failedFuture(QqClientException.protocol(endpoint, exception))
            }
        }

        val builder = HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Accept", JSON)
            .header("Authorization", accessToken.authorizationHeaderValue())
        if (requestBody != null) {
            builder.header("Content-Type", JSON)
        }
        addHeaders(builder, headers)
        return send(builder.method(method.uppercase(Locale.ROOT), publisher).build(), responseType)
    }

    private fun addHeaders(builder: HttpRequest.Builder, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            require(name.isNotBlank() && value.isNotBlank()) {
                "headers must contain non-blank names and values"
            }
            builder.header(name, value)
        }
    }

    private fun <T> send(request: HttpRequest, responseType: Class<T>): CompletionStage<T> {
        val endpoint = request.uri()
        return httpClient
            .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            .handle<T> { response, throwable ->
                if (throwable != null) {
                    throw classify(endpoint, unwrap(throwable))
                }
                if (response.statusCode() !in 200..299) {
                    throw QqClientException.httpStatus(
                        endpoint,
                        response.statusCode(),
                        decodeError(response.body()),
                    )
                }
                if (responseType == Void::class.java) {
                    return@handle nullValue()
                }
                val body = response.body()
                if (body == null || body.isBlank()) {
                    throw QqClientException.protocol(
                        endpoint,
                        IllegalStateException("QQ returned an empty success response"),
                    )
                }
                try {
                    jsonCodec.decode(body, responseType)
                } catch (exception: JsonCodecException) {
                    throw QqClientException.protocol(endpoint, exception)
                }
            }
    }

    private fun multipartBody(
        boundary: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        contentType: String,
        fileBytes: ByteArray,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        for ((name, value) in fields) {
            writeAscii(output, "--$boundary\r\n")
            writeAscii(output, "Content-Disposition: form-data; name=\"${escapeHeader(name)}\"\r\n\r\n")
            output.write(value.toByteArray(StandardCharsets.UTF_8))
            writeAscii(output, "\r\n")
        }
        writeAscii(output, "--$boundary\r\n")
        writeAscii(
            output,
            "Content-Disposition: form-data; name=\"${escapeHeader(fileField)}\"; " +
                "filename=\"${escapeHeader(fileName)}\"\r\n",
        )
        writeAscii(output, "Content-Type: ${escapeHeader(contentType)}\r\n\r\n")
        output.write(fileBytes)
        writeAscii(output, "\r\n--$boundary--\r\n")
        return output.toByteArray()
    }

    private fun writeAscii(output: ByteArrayOutputStream, value: String) {
        output.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun escapeHeader(value: String): String {
        if ('\r' in value || '\n' in value || '"' in value || '\\' in value) {
            return value.replace('\\', '_').replace('"', '_').replace('\r', '_').replace('\n', '_')
        }
        return value
    }

    private fun decodeError(body: String?): QqApiError? {
        if (body == null || body.isBlank()) {
            return null
        }
        return try {
            val error = jsonCodec.decode(body, QqApiError::class.java)
            if (error.message == null && error.traceId == null && error.code == 0) null else error
        } catch (_: JsonCodecException) {
            null
        }
    }

    companion object {
        private const val JSON = "application/json"

        private fun classify(endpoint: URI, throwable: Throwable): QqClientException = when (throwable) {
            is QqClientException -> throwable
            is HttpTimeoutException -> QqClientException.timeout(endpoint, throwable)
            is IOException -> QqClientException.transport(endpoint, throwable)
            else -> QqClientException.transport(endpoint, throwable)
        }

        private fun unwrap(throwable: Throwable): Throwable {
            var current = throwable
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
                current = current.cause!!
            }
            return current
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> nullValue(): T = null as T
    }
}
