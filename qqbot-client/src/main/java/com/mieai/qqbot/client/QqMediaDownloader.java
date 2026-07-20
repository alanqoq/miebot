package com.mieai.qqbot.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Bounded, redirect-aware downloader used before handing media to QQ. */
public final class QqMediaDownloader {
    private final HttpClient client;
    private final long maxBytes;
    private final Duration timeout;
    private final int maxRedirects;

    public QqMediaDownloader(long maxBytes, Duration timeout, int maxRedirects) {
        if (maxBytes < 1L) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxRedirects < 0 || maxRedirects > 8) throw new IllegalArgumentException("maxRedirects is invalid");
        this.maxRedirects = maxRedirects;
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public CompletionStage<byte[]> download(URI source) {
        return CompletableFuture.supplyAsync(() -> downloadSync(source));
    }

    private byte[] downloadSync(URI source) {
        URI current = validate(source);
        for (int redirect = 0; ; redirect++) {
            HttpRequest request = HttpRequest.newBuilder(current).timeout(timeout)
                    .header("Accept", "*/*").GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    response.body().close();
                    if (redirect >= maxRedirects) throw new IllegalArgumentException("media URL exceeded redirect limit");
                    String location = response.headers().firstValue("location").orElseThrow(
                            () -> new IllegalArgumentException("media redirect has no Location header"));
                    current = validate(current.resolve(location));
                    continue;
                }
                if (status < 200 || status >= 300) {
                    response.body().close();
                    throw new IllegalArgumentException("media URL returned HTTP " + status);
                }
                long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
                if (declared > maxBytes) throw new IllegalArgumentException("media download exceeds configured limit");
                try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    long total = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > maxBytes) throw new IllegalArgumentException("media download exceeds configured limit");
                        output.write(buffer, 0, read);
                    }
                    return output.toByteArray();
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to download media URL", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Media download was interrupted", exception);
            }
        }
    }

    private static URI validate(URI value) {
        Objects.requireNonNull(value, "media URL must not be null");
        if (!value.isAbsolute() || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null
                || value.toString().length() > 2048) {
            throw new IllegalArgumentException("media URL must be an HTTPS URL without credentials or fragments");
        }
        String host = value.getHost();
        if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")) {
            throw new IllegalArgumentException("media URL host is not public");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (privateAddress(address)) throw new IllegalArgumentException("media URL target is private");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalArgumentException("media URL host cannot be resolved", exception);
        }
        return value;
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        if (address instanceof Inet6Address v6) {
            byte[] bytes = v6.getAddress();
            return (bytes[0] & 0xfe) == 0xfc; // IPv6 ULA (fc00::/7)
        }
        return false;
    }
}
