package com.mieai.qqbot.runtime.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.BotCredentials;
import com.mieai.qqbot.client.QqAccessTokenClient;
import com.mieai.qqbot.client.QqClientException;
import com.mieai.qqbot.client.QqClientFailure;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.QqMediaMessageRequest;
import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.client.QqMessageSendResult;
import com.mieai.qqbot.client.QqTextMessageRequest;
import com.mieai.qqbot.client.QqRichMessageRequest;
import com.mieai.qqbot.client.SingleFlightTokenProvider;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.outbox.OutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxStatus;
import com.mieai.qqbot.persistence.lease.BotLeaseRepository;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.client.MediaAsset;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.runtime.security.BotCredentialDecryptor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Claims durable message jobs and sends them through bot-scoped QQ OpenAPI clients. */
public final class ProductionOutboxWorker implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProductionOutboxWorker.class);

    private final OutboxRepository outbox;
    private final BotRepository bots;
    private final BotCredentialDecryptor credentials;
    private final Function<BotEnvironment, QqClientOptions> optionsResolver;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration leaseDuration;
    private final Duration requestWait;
    private final int maxAttempts;
    private final int batchSize;
    private final BotLeaseRepository botLeases;
    private final String instanceId;
    private final MediaAssetStore mediaStore;
    private final String workerId = "outbox-worker-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object transitionMonitor = new Object();
    private final Map<BotId, ClientHandle> clients = new HashMap<>();
    private volatile boolean databaseTransitionActive;
    private ScheduledFuture<?> polling;

    public ProductionOutboxWorker(OutboxRepository outbox, BotRepository bots, AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver, ObjectMapper mapper,
            ScheduledExecutorService scheduler, Clock clock, Duration pollInterval,
            Duration leaseDuration, Duration requestWait, int maxAttempts, int batchSize) {
        this(outbox, bots, secretCipher, optionsResolver, mapper, scheduler, clock, pollInterval,
                leaseDuration, requestWait, maxAttempts, batchSize, null, null, null);
    }

    public ProductionOutboxWorker(OutboxRepository outbox, BotRepository bots, AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver, ObjectMapper mapper,
            ScheduledExecutorService scheduler, Clock clock, Duration pollInterval,
            Duration leaseDuration, Duration requestWait, int maxAttempts, int batchSize,
            BotLeaseRepository botLeases, String instanceId) {
        this(outbox, bots, secretCipher, optionsResolver, mapper, scheduler, clock, pollInterval,
                leaseDuration, requestWait, maxAttempts, batchSize, botLeases, instanceId, null);
    }

    public ProductionOutboxWorker(OutboxRepository outbox, BotRepository bots, AppSecretCipher secretCipher,
            Function<BotEnvironment, QqClientOptions> optionsResolver, ObjectMapper mapper,
            ScheduledExecutorService scheduler, Clock clock, Duration pollInterval,
            Duration leaseDuration, Duration requestWait, int maxAttempts, int batchSize,
            BotLeaseRepository botLeases, String instanceId, MediaAssetStore mediaStore) {
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        credentials = new BotCredentialDecryptor(secretCipher);
        this.optionsResolver = Objects.requireNonNull(optionsResolver, "optionsResolver must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.pollInterval = positive(pollInterval, "pollInterval");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.requestWait = positive(requestWait, "requestWait");
        if (leaseDuration.compareTo(requestWait) <= 0) throw new IllegalArgumentException("leaseDuration must exceed requestWait");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("batchSize is invalid");
        this.maxAttempts = maxAttempts;
        this.batchSize = batchSize;
        this.botLeases = botLeases;
        this.instanceId = botLeases == null ? null : Objects.requireNonNull(instanceId, "instanceId must not be null");
        this.mediaStore = mediaStore;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        polling = scheduler.scheduleWithFixedDelay(this::tickSafely, 0,
                pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public boolean isRunning() { return running.get(); }

    public void beforeActiveDatabaseChange() {
        synchronized (transitionMonitor) {
            databaseTransitionActive = true;
            clearClients();
        }
    }

    public void activeDatabaseChanged() {
        synchronized (transitionMonitor) {
            clearClients();
            databaseTransitionActive = false;
        }
    }

    private void tickSafely() {
        if (!running.get() || databaseTransitionActive) return;
        synchronized (transitionMonitor) {
            if (!running.get() || databaseTransitionActive) return;
            try {
                for (int index = 0; index < batchSize && processOne(); index++) {}
            } catch (RuntimeException exception) {
                LOGGER.warn("Outbox poll failed ({})", exception.getClass().getSimpleName());
            }
        }
    }

    private boolean processOne() {
        Instant now = clock.instant();
        Optional<OutboxJob> claimed = botLeases == null
                ? outbox.claimNext(workerId, now, leaseDuration)
                : outbox.claimNextOwned(workerId, instanceId, now, leaseDuration);
        if (claimed.isEmpty()) return false;
        OutboxJob job = claimed.get();
        MediaAsset stagedAsset = null;
        try {
            if (!OutboundTextPayload.JOB_TYPE.equals(job.jobType())
                    && !OutboundMediaPayload.JOB_TYPE.equals(job.jobType())
                    && !OutboundRichPayload.JOB_TYPE.equals(job.jobType())) {
                outbox.markDeadLetter(job.id(), job.fencingToken(), clock.instant(), "Unsupported Outbox job type");
                return true;
            }
            StoredBot bot = bots.findById(job.botId()).orElseThrow(() -> new PermanentFailure("Bot no longer exists"));
            if (botLeases != null && !botLeases.isOwned(bot.id(), bot.definition().shardSpec().index(), instanceId, clock.instant())) {
                throw new RetryableFailure("Bot lease is no longer owned by this instance");
            }
            if (!bot.definition().enabled()) throw new RetryableFailure("Bot is disabled");
            if (bot.definition().environment() != job.environment()) {
                throw new PermanentFailure("Outbox environment does not match bot environment");
            }
            if (OutboundTextPayload.JOB_TYPE.equals(job.jobType())) {
                OutboundTextPayload payload = mapper.readValue(job.payload(), OutboundTextPayload.class);
                QqTextMessageRequest request = new QqTextMessageRequest(
                        payload.targetType(), payload.targetId(), payload.content(),
                        Optional.ofNullable(payload.replyMessageId()), Optional.ofNullable(payload.replyEventId()),
                        payload.messageSequence());
                client(bot).sendText(request).toCompletableFuture()
                        .get(requestWait.toMillis(), TimeUnit.MILLISECONDS);
            } else if (OutboundMediaPayload.JOB_TYPE.equals(job.jobType())) {
                OutboundMediaPayload payload = mapper.readValue(job.payload(), OutboundMediaPayload.class);
                QqMediaMessageRequest request = new QqMediaMessageRequest(
                        payload.targetType(), payload.targetId(), payload.mediaKind(), java.net.URI.create(payload.mediaUrl()),
                        Optional.ofNullable(payload.content()), Optional.ofNullable(payload.replyMessageId()),
                        Optional.ofNullable(payload.replyEventId()), payload.messageSequence());
                if (payload.mediaAssetId() == null) {
                    CompletionStage<QqMessageSendResult> sending = mediaStore == null
                            ? client(bot).sendMedia(request)
                            : client(bot).sendMediaBounded(request);
                    sending.toCompletableFuture()
                            .get(requestWait.toMillis(), TimeUnit.MILLISECONDS);
                } else {
                    if (mediaStore == null) throw new PermanentFailure("Local media staging is not configured");
                    stagedAsset = mediaStore.find(bot.id(), payload.mediaAssetId()).orElseThrow(
                            () -> new PermanentFailure("Staged media asset is no longer available"));
                    if (stagedAsset.kind() != request.mediaKind()) {
                        throw new PermanentFailure("Staged media kind does not match the Outbox payload");
                    }
                    if (stagedAsset.sizeBytes() > bot.definition().maxMediaUploadBytes()) {
                        throw new PermanentFailure("Staged media exceeds the bot upload limit");
                    }
                    byte[] bytes;
                    try (var input = mediaStore.open(stagedAsset)) {
                        bytes = input.readNBytes(Math.toIntExact(bot.definition().maxMediaUploadBytes() + 1L));
                    }
                    if (bytes.length > bot.definition().maxMediaUploadBytes()) {
                        throw new PermanentFailure("Staged media exceeds the bot upload limit");
                    }
                    client(bot).sendMedia(request, bytes).toCompletableFuture()
                            .get(requestWait.toMillis(), TimeUnit.MILLISECONDS);
                }
            } else {
                OutboundRichPayload payload = mapper.readValue(job.payload(), OutboundRichPayload.class);
                QqRichMessageRequest request = new QqRichMessageRequest(
                        payload.targetType(), payload.targetId(), payload.kind(), payload.payload(),
                        Optional.ofNullable(payload.replyMessageId()), Optional.ofNullable(payload.replyEventId()),
                        payload.messageSequence());
                client(bot).sendRich(request).toCompletableFuture()
                        .get(requestWait.toMillis(), TimeUnit.MILLISECONDS);
            }
            outbox.markSucceeded(job.id(), job.fencingToken(), clock.instant());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            retry(job, exception);
        } catch (TimeoutException exception) {
            resultUnknown(job, "QQ request timed out after it may have been sent");
        } catch (ExecutionException | CompletionException exception) {
            classify(job, unwrap(exception));
        } catch (PermanentFailure | IllegalArgumentException | java.io.IOException exception) {
            deadLetter(job, safeError(exception));
        } catch (RetryableFailure exception) {
            retry(job, exception);
        } catch (RuntimeException exception) {
            retry(job, exception);
        } finally {
            if (stagedAsset != null) {
                try {
                    OutboxStatus status = outbox.findById(job.id()).map(OutboxJob::status).orElse(null);
                    if (status != null && status.isTerminal()) mediaStore.delete(stagedAsset);
                } catch (RuntimeException cleanupFailure) {
                    LOGGER.warn("Could not clean staged media asset for job {} ({})",
                            job.id(), cleanupFailure.getClass().getSimpleName());
                }
            }
        }
        return true;
    }

    private QqOpenApiClient client(StoredBot bot) {
        BotId id = bot.id();
        ClientHandle current = clients.get(id);
        long revision = bot.definition().revision().value();
        if (current != null && current.revision() == revision
                && current.environment() == bot.definition().environment()) return current.client();
        if (current != null) current.close();
        BotCredentials decrypted = credentials.decrypt(bot);
        try {
            QqClientOptions baseOptions = Objects.requireNonNull(optionsResolver.apply(bot.definition().environment()),
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
            SingleFlightTokenProvider provider = new SingleFlightTokenProvider(decrypted,
                    new QqAccessTokenClient(options), options);
            QqOpenApiClient client =
                    new QqOpenApiClient(options, provider, decrypted.appId());
            clients.put(id, new ClientHandle(revision, bot.definition().environment(), client, decrypted));
            return client;
        } catch (RuntimeException exception) {
            decrypted.close();
            throw exception;
        }
    }

    private void classify(OutboxJob job, Throwable failure) {
        if (failure instanceof IllegalArgumentException) {
            deadLetter(job, safeError(failure));
            return;
        }
        if (!(failure instanceof QqClientException qq)) {
            retry(job, failure);
            return;
        }
        if (qq.failure() == QqClientFailure.TIMEOUT || qq.failure() == QqClientFailure.PROTOCOL) {
            resultUnknown(job, safeError(qq));
            return;
        }
        if (qq.failure() == QqClientFailure.TRANSPORT || qq.failure() == QqClientFailure.AUTHENTICATION) {
            retry(job, qq);
            return;
        }
        int status = qq.httpStatus().orElse(0);
        if (status == 408 || status == 409 || status == 425 || status == 429 || status >= 500) {
            retry(job, qq);
        } else {
            deadLetter(job, safeError(qq));
        }
    }

    private void retry(OutboxJob job, Throwable failure) {
        Instant now = clock.instant();
        String error = safeError(failure);
        try {
            if (job.attempt() >= maxAttempts) outbox.markDeadLetter(job.id(), job.fencingToken(), now, error);
            else outbox.markRetry(job.id(), job.fencingToken(), now, now.plus(backoff(job.attempt())), error);
        } catch (RuntimeException transition) {
            LOGGER.warn("Could not retry Outbox job {} ({})", job.id(), transition.getClass().getSimpleName());
        }
    }

    private void deadLetter(OutboxJob job, String reason) {
        try {
            outbox.markDeadLetter(job.id(), job.fencingToken(), clock.instant(), reason);
        } catch (RuntimeException transition) {
            LOGGER.warn("Could not dead-letter Outbox job {} ({})", job.id(), transition.getClass().getSimpleName());
        }
    }

    private void resultUnknown(OutboxJob job, String reason) {
        try {
            outbox.markResultUnknown(job.id(), job.fencingToken(), clock.instant(), reason);
        } catch (RuntimeException transition) {
            LOGGER.warn("Could not mark Outbox job {} result unknown ({})", job.id(), transition.getClass().getSimpleName());
        }
    }

    private void clearClients() {
        clients.values().forEach(ClientHandle::close);
        clients.clear();
    }

    private static Duration backoff(long attempt) {
        long seconds = Math.min(300L, 1L << Math.min(Math.max(attempt - 1L, 0L), 8L));
        return Duration.ofSeconds(seconds);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String safeError(Throwable failure) {
        Throwable current = unwrap(failure);
        String value = current.getClass().getSimpleName();
        if (current instanceof QqClientException qq) {
            value += "[" + qq.failure() + "]";
            if (qq.httpStatus().isPresent()) value += " HTTP " + qq.httpStatus().getAsInt();
            if (qq.qqCode().isPresent()) value += " QQ " + qq.qqCode().getAsInt();
        } else if (current.getMessage() != null && !current.getMessage().isBlank()) {
            value += ": " + current.getMessage().replace('\n', ' ').replace('\r', ' ');
        }
        return value.length() > 512 ? value.substring(0, 512) : value;
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) return;
        ScheduledFuture<?> task = polling;
        if (task != null) task.cancel(false);
        synchronized (transitionMonitor) { clearClients(); }
    }

    private record ClientHandle(long revision, BotEnvironment environment, QqOpenApiClient client,
            BotCredentials credentials) implements AutoCloseable {
        @Override public void close() { credentials.close(); }
    }

    private static final class PermanentFailure extends RuntimeException {
        private PermanentFailure(String message) { super(message); }
    }
    private static final class RetryableFailure extends RuntimeException {
        private RetryableFailure(String message) { super(message); }
    }
}
