package com.mieai.qqbot.admin.events;

import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.database.DatabaseConfigurationView;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.outbox.OutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxPage;
import com.mieai.qqbot.persistence.outbox.OutboxQuery;
import com.mieai.qqbot.persistence.outbox.OutboxQueueStats;
import com.mieai.qqbot.persistence.outbox.OutboxRepository;
import com.mieai.qqbot.persistence.outbox.OutboxStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Validates Outbox/DLQ query input and maps durable jobs to the administrator API contract. */
@Service
public class OutboxAdministrationService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_LENGTH = 256;
    public static final int MAX_JOB_TYPE_LENGTH = 128;
    public static final int MAX_CURSOR_LENGTH = 512;
    public static final int MAX_ERROR_CHARACTERS = 1024;
    public static final int MAX_DETAIL_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_CONSISTENCY_ATTEMPTS = 3;

    private final OutboxRepository outboxRepository;
    private final BotRepository botRepository;
    /** Optional for focused adapter tests; present in the production application. */
    private final ObjectProvider<DatabaseAdministrationService> databaseServiceProvider;

    public OutboxAdministrationService(
            OutboxRepository outboxRepository,
            BotRepository botRepository) {
        this(outboxRepository, botRepository, null);
    }

    @Autowired
    public OutboxAdministrationService(
            OutboxRepository outboxRepository,
            BotRepository botRepository,
            ObjectProvider<DatabaseAdministrationService> databaseServiceProvider) {
        this.outboxRepository = Objects.requireNonNull(outboxRepository, "outboxRepository must not be null");
        this.botRepository = Objects.requireNonNull(botRepository, "botRepository must not be null");
        this.databaseServiceProvider = databaseServiceProvider;
    }

    public OutboxPageResponse list(
            String limit,
            String cursor,
            String query,
            String botId,
            String environment,
            String status,
            String jobType) {
        return list(limit, cursor, query, botId, environment, status, jobType, false);
    }

    public OutboxPageResponse list(
            String limit,
            String cursor,
            String query,
            String botId,
            String environment,
            String status,
            String jobType,
            boolean deadLetterOnly) {
        int parsedLimit = parseLimit(limit);
        Optional<String> parsedCursor = optionalText(cursor, "cursor", MAX_CURSOR_LENGTH);
        Optional<String> parsedQuery = optionalText(query, "query", MAX_QUERY_LENGTH);
        Optional<BotId> parsedBotId = parseBotId(botId);
        Optional<BotEnvironment> parsedEnvironment = parseEnvironment(environment);
        Optional<OutboxStatus> parsedStatus = deadLetterOnly
                ? parseDeadLetterStatus(status)
                : parseStatus(status);
        Optional<OutboxStatus> effectiveStatus = deadLetterOnly
                ? Optional.of(OutboxStatus.DEAD_LETTER)
                : parsedStatus;
        Optional<String> parsedJobType = optionalToken(jobType, "jobType", MAX_JOB_TYPE_LENGTH);

        OutboxQuery outboxQuery = new OutboxQuery(
                parsedLimit,
                parsedCursor,
                parsedEnvironment,
                parsedBotId,
                effectiveStatus,
                parsedJobType,
                parsedQuery);
        return readConsistently(() -> {
            OutboxPage page = outboxRepository.query(outboxQuery);
            Map<BotId, BotMetadata> bots = loadBotMetadata();
            return new OutboxPageResponse(
                    page.jobs().stream()
                            .map(job -> summary(job, bots.get(job.botId())))
                            .toList(),
                    page.nextCursor().orElse(null),
                    page.nextCursor().isPresent(),
                    Instant.now(),
                    OutboxQueueStatsResponse.from(outboxRepository.statistics()));
        });
    }

    public OutboxJobDetailResponse get(String id) {
        return get(id, false);
    }

    public OutboxJobDetailResponse get(String id, boolean deadLetterOnly) {
        UUID parsedId = parseUuid(id, "id");
        return readConsistently(() -> {
            OutboxJob job = outboxRepository.findById(parsedId)
                    .filter(candidate -> !deadLetterOnly || candidate.status() == OutboxStatus.DEAD_LETTER)
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "Outbox job was not found: " + id));
            return detail(job, loadBotMetadata().get(job.botId()));
        });
    }

    public OutboxQueueStatsResponse statistics() {
        return readConsistently(() -> OutboxQueueStatsResponse.from(outboxRepository.statistics()));
    }

    /**
     * A database switch swaps the DataSource behind both repositories. Re-read a bounded number
     * of times when the revision changes during related queries, so one HTTP response does not
     * combine a job row from one database with bot metadata from another.
     */
    private <T> T readConsistently(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        DatabaseAdministrationService databaseService = databaseServiceProvider == null
                ? null
                : databaseServiceProvider.getIfAvailable();
        if (databaseService == null) {
            return operation.get();
        }

        T last = null;
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < MAX_CONSISTENCY_ATTEMPTS; attempt++) {
            DatabaseConfigurationView before = databaseService.current();
            T result = null;
            RuntimeException failure = null;
            try {
                result = operation.get();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            DatabaseConfigurationView after = databaseService.current();
            if (failure == null) {
                last = result;
            } else {
                lastFailure = failure;
            }
            if (consistent(before, after)) {
                if (failure != null) {
                    throw failure;
                }
                return result;
            }
        }
        if (last == null && lastFailure != null) {
            throw lastFailure;
        }
        return last;
    }

    private static boolean consistent(
            DatabaseConfigurationView before,
            DatabaseConfigurationView after) {
        return !before.switchInProgress()
                && !after.switchInProgress()
                && before.revision() == after.revision();
    }

    private Map<BotId, BotMetadata> loadBotMetadata() {
        Map<BotId, BotMetadata> result = new LinkedHashMap<>();
        for (StoredBot stored : botRepository.findAll()) {
            result.put(stored.id(), new BotMetadata(
                    stored.definition().displayName(),
                    stored.definition().appId().value()));
        }
        return Map.copyOf(result);
    }

    private static OutboxJobSummaryResponse summary(OutboxJob job, BotMetadata bot) {
        return new OutboxJobSummaryResponse(
                job.id(),
                job.environment().name(),
                job.botId().value(),
                bot == null ? null : bot.displayName(),
                bot == null ? null : bot.appId(),
                job.sourceEventId().orElse(null),
                job.jobType(),
                job.status().name(),
                job.attempt(),
                job.availableAt(),
                job.createdAt(),
                job.updatedAt(),
                job.completedAt().orElse(null),
                limitError(job.lastError().orElse(null)));
    }

    private static OutboxJobDetailResponse detail(OutboxJob job, BotMetadata bot) {
        String payload = truncateUtf8(job.payload(), MAX_DETAIL_PAYLOAD_BYTES);
        return new OutboxJobDetailResponse(
                job.id(),
                job.environment().name(),
                job.botId().value(),
                bot == null ? null : bot.displayName(),
                bot == null ? null : bot.appId(),
                job.sourceEventId().orElse(null),
                job.jobType(),
                job.dedupKey().orElse(null),
                job.status().name(),
                job.attempt(),
                job.availableAt(),
                job.leaseUntil().orElse(null),
                job.createdAt(),
                job.updatedAt(),
                job.completedAt().orElse(null),
                limitError(job.lastError().orElse(null)),
                payload,
                !payload.equals(job.payload()));
    }

    private static String limitError(String value) {
        if (value == null || value.length() <= MAX_ERROR_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAX_ERROR_CHARACTERS);
    }

    private static String truncateUtf8(String value, int maxBytes) {
        int encodedBytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int codePointBytes = utf8Bytes(codePoint);
            if (encodedBytes > maxBytes - codePointBytes) {
                break;
            }
            encodedBytes += codePointBytes;
            end += Character.charCount(codePoint);
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7f) {
            return 1;
        }
        if (codePoint <= 0x7ff) {
            return 2;
        }
        if (codePoint <= 0xffff) {
            return 3;
        }
        return 4;
    }

    private static int parseLimit(String value) {
        String candidate = value == null || value.isBlank()
                ? Integer.toString(DEFAULT_LIMIT)
                : value.strip();
        try {
            int parsed = Integer.parseInt(candidate);
            if (parsed < 1 || parsed > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be an integer", exception);
        }
    }

    private static Optional<String> optionalText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        if (normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return Optional.of(normalized);
    }

    private static Optional<String> optionalToken(String value, String name, int maxLength) {
        Optional<String> normalized = optionalText(value, name, maxLength);
        normalized.ifPresent(token -> {
            if (token.codePoints().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException(name + " must not contain whitespace");
            }
        });
        return normalized;
    }

    private static Optional<BotId> parseBotId(String value) {
        return optionalText(value, "botId", 64).map(BotId::parse);
    }

    private static Optional<BotEnvironment> parseEnvironment(String value) {
        return optionalText(value, "environment", 32).map(candidate -> parseEnum(
                candidate, BotEnvironment.class, "environment"));
    }

    private static Optional<OutboxStatus> parseStatus(String value) {
        return optionalText(value, "status", 32).map(candidate -> parseEnum(
                candidate, OutboxStatus.class, "status"));
    }

    private static Optional<OutboxStatus> parseDeadLetterStatus(String value) {
        Optional<String> candidate = optionalText(value, "status", 32);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        if (!OutboxStatus.DEAD_LETTER.name().equalsIgnoreCase(candidate.get())) {
            throw new IllegalArgumentException("status must be DEAD_LETTER for the DLQ view");
        }
        return Optional.of(OutboxStatus.DEAD_LETTER);
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, String name) {
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " is not supported: " + value, exception);
        }
    }

    private static UUID parseUuid(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(name + " must use canonical UUID format");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("canonical")) {
                throw exception;
            }
            throw new IllegalArgumentException(name + " must be a canonical UUID", exception);
        }
    }

    private record BotMetadata(String displayName, String appId) {
    }
}
