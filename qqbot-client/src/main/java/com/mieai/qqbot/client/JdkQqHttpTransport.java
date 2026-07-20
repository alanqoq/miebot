package com.mieai.qqbot.client;

import com.mieai.qqbot.protocol.error.QqApiError;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

final class JdkQqHttpTransport {
    private static final String JSON = "application/json";

    private final HttpClient httpClient;
    private final JsonCodec jsonCodec;
    private final Duration requestTimeout;

    JdkQqHttpTransport(JsonCodec jsonCodec, Duration requestTimeout) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        httpClient = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    <T> CompletionStage<T> postJson(URI endpoint, Object requestBody, Class<T> responseType) {
        String encoded;
        try {
            encoded = jsonCodec.encode(requestBody);
        } catch (JsonCodecException exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    QqClientException.protocol(endpoint, exception));
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("Content-Type", JSON)
                .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
                .build();
        return send(request, responseType);
    }

    <T> CompletionStage<T> postAuthorizedJson(
            URI endpoint, AccessToken accessToken, Object requestBody, Class<T> responseType) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        String encoded;
        try {
            encoded = jsonCodec.encode(requestBody);
        } catch (JsonCodecException exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    QqClientException.protocol(endpoint, exception));
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("Content-Type", JSON)
                .header("Authorization", accessToken.authorizationHeaderValue())
                .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8))
                .build();
        return send(request, responseType);
    }

    /** Sends a bounded multipart request used by QQ's channel/direct image endpoint. */
    <T> CompletionStage<T> postAuthorizedMultipart(
            URI endpoint,
            AccessToken accessToken,
            Map<String, String> fields,
            String fileField,
            String fileName,
            String contentType,
            byte[] fileBytes,
            Class<T> responseType) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        Objects.requireNonNull(fields, "fields must not be null");
        Objects.requireNonNull(fileField, "fileField must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(fileBytes, "fileBytes must not be null");
        String boundary = "----qqbot-" + java.util.UUID.randomUUID();
        byte[] body;
        try {
            body = multipartBody(boundary, fields, fileField, fileName, contentType, fileBytes);
        } catch (IOException exception) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    QqClientException.protocol(endpoint, exception));
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", accessToken.authorizationHeaderValue())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request, responseType);
    }

    <T> CompletionStage<T> getJson(URI endpoint, AccessToken accessToken, Class<T> responseType) {
        Objects.requireNonNull(accessToken, "accessToken must not be null");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("Authorization", accessToken.authorizationHeaderValue())
                .GET()
                .build();
        return send(request, responseType);
    }

    private <T> CompletionStage<T> send(HttpRequest request, Class<T> responseType) {
        URI endpoint = request.uri();
        return httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        throw classify(endpoint, unwrap(throwable));
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw QqClientException.httpStatus(
                                endpoint, response.statusCode(), decodeError(response.body()));
                    }
                    try {
                        return jsonCodec.decode(response.body(), responseType);
                    } catch (JsonCodecException exception) {
                        throw QqClientException.protocol(endpoint, exception);
                    }
                });
    }

    private static byte[] multipartBody(
            String boundary,
            Map<String, String> fields,
            String fileField,
            String fileName,
            String contentType,
            byte[] fileBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            writeAscii(output, "--" + boundary + "\r\n");
            writeAscii(output, "Content-Disposition: form-data; name=\""
                    + escapeHeader(entry.getKey()) + "\"\r\n\r\n");
            output.write(entry.getValue() == null ? new byte[0]
                    : entry.getValue().getBytes(StandardCharsets.UTF_8));
            writeAscii(output, "\r\n");
        }
        writeAscii(output, "--" + boundary + "\r\n");
        writeAscii(output, "Content-Disposition: form-data; name=\""
                + escapeHeader(fileField) + "\"; filename=\""
                + escapeHeader(fileName) + "\"\r\n");
        writeAscii(output, "Content-Type: " + escapeHeader(contentType) + "\r\n\r\n");
        output.write(fileBytes);
        writeAscii(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static String escapeHeader(String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('"') >= 0 || value.indexOf('\\') >= 0) {
            return value.replace("\\", "_").replace("\"", "_")
                    .replace('\r', '_').replace('\n', '_');
        }
        return value;
    }

    private QqApiError decodeError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            QqApiError error = jsonCodec.decode(body, QqApiError.class);
            return error.message() == null && error.traceId() == null && error.code() == 0
                    ? null
                    : error;
        } catch (JsonCodecException ignored) {
            return null;
        }
    }

    private static QqClientException classify(URI endpoint, Throwable throwable) {
        if (throwable instanceof QqClientException exception) {
            return exception;
        }
        if (throwable instanceof HttpTimeoutException) {
            return QqClientException.timeout(endpoint, throwable);
        }
        if (throwable instanceof IOException) {
            return QqClientException.transport(endpoint, throwable);
        }
        return QqClientException.transport(endpoint, throwable);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
