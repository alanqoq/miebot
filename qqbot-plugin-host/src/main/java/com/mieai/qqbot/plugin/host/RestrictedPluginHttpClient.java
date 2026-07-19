package com.mieai.qqbot.plugin.host;

import com.mieai.qqbot.plugin.api.PluginHttpRequest;
import com.mieai.qqbot.plugin.api.PluginHttpResponse;
import com.mieai.qqbot.plugin.api.RestrictedHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTPS-only client with no redirects, credentials, sensitive headers or private-network targets. */
final class RestrictedPluginHttpClient implements RestrictedHttpClient, AutoCloseable {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> BLOCKED_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "host", "connection",
            "content-length", "transfer-encoding", "upgrade");
    private final ExecutorService executor = Executors.newFixedThreadPool(2, daemonFactory());
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(executor)
            .build();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Override
    public CompletionStage<PluginHttpResponse> send(PluginHttpRequest request) {
        try {
            if (closed.get()) throw new IllegalStateException("Plugin HTTP client is closed");
            validateUri(request.uri());
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
            for (Map.Entry<String, String> header : request.headers().entrySet()) {
                String name = header.getKey();
                if (name == null || BLOCKED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    throw new SecurityException("HTTP header is not permitted: " + name);
                }
                builder.header(name, header.getValue());
            }
            byte[] requestBody = request.body().orElseGet(() -> new byte[0]);
            builder.method(request.method(), requestBody.length == 0
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(requestBody));
            return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                    .thenApply(response -> {
                        if (response.statusCode() >= 300 && response.statusCode() < 400) {
                            throw new SecurityException("HTTP redirects are not permitted");
                        }
                        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                        if (contentLength > MAX_RESPONSE_BYTES) {
                            closeQuietly(response.body());
                            throw new IllegalStateException("HTTP response exceeds 2 MiB");
                        }
                        try {
                            byte[] body = readBounded(response.body());
                            return new PluginHttpResponse(response.statusCode(), response.headers().map(), body);
                        } catch (IOException exception) {
                            throw new IllegalStateException("Unable to read HTTP response", exception);
                        }
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            client.close();
            executor.shutdownNow();
        }
    }

    private static void validateUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new SecurityException("Only public HTTPS URLs without credentials or fragments are permitted");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    throw new SecurityException("Private-network HTTP targets are not permitted");
                }
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("HTTP target cannot be resolved", exception);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count > MAX_RESPONSE_BYTES - total) {
                    throw new IllegalStateException("HTTP response exceeds 2 MiB");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }

    private static void closeQuietly(InputStream input) {
        try { input.close(); } catch (IOException ignored) {}
    }

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "qqbot-plugin-http");
            thread.setDaemon(true);
            return thread;
        };
    }
}
