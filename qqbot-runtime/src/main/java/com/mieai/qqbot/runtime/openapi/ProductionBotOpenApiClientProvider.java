package com.mieai.qqbot.runtime.openapi;

import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.client.QqAccessTokenClient;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.client.SingleFlightTokenProvider;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Revision-aware client cache shared by modules that call bot-scoped QQ OpenAPI operations. */
public final class ProductionBotOpenApiClientProvider implements BotOpenApiClientProvider {
    private final BotRepository bots;
    private final BotCredentialDecryptor credentials;
    private final Function<BotEnvironment, QqClientOptions> optionsResolver;
    private final Map<BotId, ClientHandle> clients = new HashMap<>();
    private boolean closed;

    public ProductionBotOpenApiClientProvider(
            BotRepository bots,
            AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver) {
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        credentials = new BotCredentialDecryptor(secretCipher);
        this.optionsResolver = Objects.requireNonNull(
                optionsResolver, "optionsResolver must not be null");
    }

    @Override
    public synchronized QqOpenApiClient clientFor(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        ensureOpen();
        StoredBot bot = bots.findById(botId).orElseThrow(() -> new BotNotFoundException(botId));
        ClientHandle current = clients.get(botId);
        long revision = bot.definition().revision().value();
        if (current != null && current.revision() == revision
                && current.environment() == bot.definition().environment()) {
            return current.client();
        }

        QqClientOptions baseOptions = Objects.requireNonNull(
                optionsResolver.apply(bot.definition().environment()),
                "optionsResolver returned null");
        QqClientOptions options = QqClientOptions.builder()
                .tokenEndpoint(baseOptions.tokenEndpoint())
                .openApiBaseUri(baseOptions.openApiBaseUri())
                .requestTimeout(baseOptions.requestTimeout())
                .tokenRefreshSkew(baseOptions.tokenRefreshSkew())
                .maxMediaBytes(bot.definition().maxMediaUploadBytes())
                .mediaDownloadTimeout(baseOptions.mediaDownloadTimeout())
                .maxMediaRedirects(baseOptions.maxMediaRedirects())
                .build();

        BotCredentials decrypted = credentials.decrypt(bot);
        try {
            SingleFlightTokenProvider tokenProvider = new SingleFlightTokenProvider(
                    decrypted, new QqAccessTokenClient(options), options);
            QqOpenApiClient client = new QqOpenApiClient(
                    options, tokenProvider, decrypted.appId());
            ClientHandle replacement = new ClientHandle(
                    revision, bot.definition().environment(), client, decrypted);
            clients.put(botId, replacement);
            closeQuietly(current);
            return client;
        } catch (RuntimeException exception) {
            decrypted.close();
            throw exception;
        }
    }

    @Override
    public synchronized void invalidate(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        closeQuietly(clients.remove(botId));
    }

    @Override
    public synchronized void invalidateAll() {
        clients.values().forEach(ProductionBotOpenApiClientProvider::closeQuietly);
        clients.clear();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        invalidateAll();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The bot OpenAPI client provider is closed");
        }
    }

    private static void closeQuietly(ClientHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.credentials().close();
        } catch (RuntimeException ignored) {
            // Continue invalidating the remaining bot clients.
        }
    }

    private record ClientHandle(
            long revision,
            BotEnvironment environment,
            QqOpenApiClient client,
            BotCredentials credentials) {}
}
