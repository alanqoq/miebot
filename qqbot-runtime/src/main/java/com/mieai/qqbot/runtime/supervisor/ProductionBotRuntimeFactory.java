package com.mieai.qqbot.runtime.supervisor;

import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.client.QqAccessTokenClient;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.client.SingleFlightTokenProvider;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.gateway.ExecutorGatewayScheduler;
import com.mieai.qqbot.gateway.GatewayBackoffStrategy;
import com.mieai.qqbot.gateway.GatewaySnapshotStore;
import com.mieai.qqbot.gateway.GatewayTransport;
import com.mieai.qqbot.gateway.JdkGatewayTransport;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** Production factory for one isolated QQ Gateway runtime per persisted bot. */
public final class ProductionBotRuntimeFactory implements BotRuntimeFactory {
    private static final Duration INITIAL_RECONNECT_DELAY = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_RECONNECT_DELAY = Duration.ofSeconds(30);

    private final BotCredentialDecryptor credentialDecryptor;
    private final Function<BotEnvironment, QqClientOptions> optionsResolver;
    private final GatewayTransport gatewayTransport;
    private final Function<StoredBot, GatewaySnapshotStore> snapshotStoreFactory;
    private final RuntimeSchedulerFactory schedulerFactory;
    private final GatewayBackoffStrategy backoffStrategy;

    public ProductionBotRuntimeFactory(
            AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver,
            Function<StoredBot, GatewaySnapshotStore> snapshotStoreFactory) {
        this(
                secretCipher,
                optionsResolver,
                new JdkGatewayTransport(),
                snapshotStoreFactory);
    }

    public ProductionBotRuntimeFactory(
            AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver,
            GatewayTransport gatewayTransport,
            Function<StoredBot, GatewaySnapshotStore> snapshotStoreFactory) {
        this(
                secretCipher,
                optionsResolver,
                gatewayTransport,
                snapshotStoreFactory,
                ProductionBotRuntimeFactory::newScheduler,
                GatewayBackoffStrategy.exponential(
                        INITIAL_RECONNECT_DELAY,
                        MAXIMUM_RECONNECT_DELAY,
                        ProductionBotRuntimeFactory::fullJitter));
    }

    ProductionBotRuntimeFactory(
            AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver,
            GatewayTransport gatewayTransport,
            Function<StoredBot, GatewaySnapshotStore> snapshotStoreFactory,
            RuntimeSchedulerFactory schedulerFactory,
            GatewayBackoffStrategy backoffStrategy) {
        credentialDecryptor = new BotCredentialDecryptor(secretCipher);
        this.optionsResolver =
                Objects.requireNonNull(optionsResolver, "optionsResolver must not be null");
        this.gatewayTransport =
                Objects.requireNonNull(gatewayTransport, "gatewayTransport must not be null");
        this.snapshotStoreFactory = Objects.requireNonNull(
                snapshotStoreFactory, "snapshotStoreFactory must not be null");
        this.schedulerFactory =
                Objects.requireNonNull(schedulerFactory, "schedulerFactory must not be null");
        this.backoffStrategy =
                Objects.requireNonNull(backoffStrategy, "backoffStrategy must not be null");
    }

    @Override
    public ManagedBotRuntime create(StoredBot configuration, BotRuntimeObserver observer) {
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(observer, "observer must not be null");
        BotDefinition definition = configuration.definition();
        QqClientOptions options = Objects.requireNonNull(
                optionsResolver.apply(definition.environment()),
                "optionsResolver returned null");
        GatewaySnapshotStore snapshotStore = Objects.requireNonNull(
                snapshotStoreFactory.apply(configuration),
                "snapshotStoreFactory returned null");

        BotCredentials credentials = credentialDecryptor.decrypt(configuration);
        RuntimeScheduler scheduler = null;
        try {
            scheduler = Objects.requireNonNull(
                    schedulerFactory.create(definition), "schedulerFactory returned null");
            QqAccessTokenClient tokenClient = new QqAccessTokenClient(options);
            SingleFlightTokenProvider tokenProvider =
                    new SingleFlightTokenProvider(credentials, tokenClient, options);
            QqOpenApiClient openApiClient = new QqOpenApiClient(options, tokenProvider);
            return new ProductionBotRuntime(
                    definition,
                    credentials,
                    openApiClient::getGatewayBot,
                    tokenProvider,
                    gatewayTransport,
                    snapshotStore,
                    scheduler,
                    backoffStrategy,
                    observer);
        } catch (RuntimeException exception) {
            closeQuietly(scheduler);
            credentials.close();
            throw exception;
        }
    }

    private static RuntimeScheduler newScheduler(BotDefinition definition) {
        ExecutorGatewayScheduler delegate =
                new ExecutorGatewayScheduler("qqbot-gateway-" + definition.id());
        return new RuntimeScheduler() {
            @Override
            public Cancellable schedule(Duration delay, Runnable task) {
                return delegate.schedule(delay, task);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    private static Duration fullJitter(Duration maximum) {
        long bound = maximum.toNanos();
        if (bound == 0L) {
            return Duration.ZERO;
        }
        return Duration.ofNanos(ThreadLocalRandom.current().nextLong(bound + 1L));
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // A failed factory must still destroy the decrypted credential.
        }
    }
}
