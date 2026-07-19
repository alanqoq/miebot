package com.mieai.qqbot.admin.events;

import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.database.DatabaseConfigurationView;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.inbox.InboxEvent;
import com.mieai.qqbot.persistence.inbox.InboxPage;
import com.mieai.qqbot.persistence.inbox.InboxQuery;
import com.mieai.qqbot.persistence.inbox.InboxStatus;
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

/** Validates Inbox query input and maps durable events to the administrator API contract. */
@Service
public class InboxAdministrationService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;
    public static final int MAX_QUERY_LENGTH = 128;
    public static final int MAX_EVENT_TYPE_LENGTH = 128;
    public static final int MAX_CURSOR_LENGTH = 512;
    public static final int MAX_ERROR_CHARACTERS = 1024;
    public static final int MAX_DETAIL_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_CONSISTENCY_ATTEMPTS = 3;

    private final EventInboxRepository inboxRepository;
    private final BotRepository botRepository;
    /** Optional for focused adapter tests; present in the production application. */
    private final ObjectProvider<DatabaseAdministrationService> databaseServiceProvider;

    public InboxAdministrationService(
            EventInboxRepository inboxRepository,
            BotRepository botRepository) {
        this(inboxRepository, botRepository, null);
    }

    @Autowired
    public InboxAdministrationService(
            EventInboxRepository inboxRepository,
            BotRepository botRepository,
            ObjectProvider<DatabaseAdministrationService> databaseServiceProvider) {
        this.inboxRepository = Objects.requireNonNull(inboxRepository, "inboxRepository must not be null");
        this.botRepository = Objects.requireNonNull(botRepository, "botRepository must not be null");
        this.databaseServiceProvider = databaseServiceProvider;
    }

    public InboxPageResponse list(
            String limit,
            String cursor,
            String query,
            String botId,
            String environment,
            String status,
            String eventType) {
        int parsedLimit = parseLimit(limit);
        Optional<String> parsedCursor = optionalText(cursor, "cursor", MAX_CURSOR_LENGTH);
        Optional<String> parsedQuery = optionalText(query, "query", MAX_QUERY_LENGTH);
        Optional<BotId> parsedBotId = parseBotId(botId);
        Optional<BotEnvironment> parsedEnvironment = parseEnvironment(environment);
        Optional<InboxStatus> parsedStatus = parseStatus(status);
        Optional<String> parsedEventType = optionalToken(eventType, "eventType", MAX_EVENT_TYPE_LENGTH);

        InboxQuery inboxQuery = new InboxQuery(
                parsedLimit,
                parsedCursor,
                parsedEnvironment,
                parsedBotId,
                parsedStatus,
                parsedEventType,
                parsedQuery);
        return readConsistently(() -> {
            InboxPage page = inboxRepository.query(inboxQuery);
            Map<BotId, BotMetadata> bots = loadBotMetadata();
            return new InboxPageResponse(
                    page.events().stream()
                            .map(event -> summary(event, bots.get(event.botId())))
                            .toList(),
                    page.nextCursor().orElse(null),
                    page.nextCursor().isPresent(),
                    Instant.now());
        });
    }

    public InboxEventDetailResponse get(String id) {
        UUID parsedId = parseUuid(id, "id");
        return readConsistently(() -> {
            InboxEvent event = inboxRepository.findById(parsedId)
                    .orElseThrow(() -> new java.util.NoSuchElementException(
                            "Inbox event was not found: " + id));
            return detail(event, loadBotMetadata().get(event.botId()));
        });
    }

    /**
     * A database switch swaps the DataSource behind both repositories. Re-read a bounded number
     * of times when the revision changes during the two related queries, so one HTTP response does
     * not combine an Inbox row from one database with bot metadata from another.
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

    private static InboxEventSummaryResponse summary(InboxEvent event, BotMetadata bot) {
        return new InboxEventSummaryResponse(
                event.id(),
                event.environment().name(),
                event.botId().value(),
                bot == null ? null : bot.displayName(),
                bot == null ? null : bot.appId(),
                event.eventType(),
                event.platformEventId(),
                event.status().name(),
                event.attempt(),
                event.availableAt(),
                event.receivedAt(),
                event.updatedAt(),
                limitError(event.lastError().orElse(null)));
    }

    private static InboxEventDetailResponse detail(InboxEvent event, BotMetadata bot) {
        String payload = truncateUtf8(event.payload(), MAX_DETAIL_PAYLOAD_BYTES);
        return new InboxEventDetailResponse(
                event.id(),
                event.environment().name(),
                event.botId().value(),
                bot == null ? null : bot.displayName(),
                bot == null ? null : bot.appId(),
                event.eventType(),
                event.platformEventId(),
                event.status().name(),
                event.attempt(),
                event.availableAt(),
                event.receivedAt(),
                event.updatedAt(),
                limitError(event.lastError().orElse(null)),
                payload,
                !payload.equals(event.payload()));
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
        String candidate = value == null || value.isBlank() ? Integer.toString(DEFAULT_LIMIT) : value.strip();
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
        return optionalText(value, "botId", 64).map(candidate -> BotId.parse(candidate));
    }

    private static Optional<BotEnvironment> parseEnvironment(String value) {
        return optionalText(value, "environment", 32).map(candidate -> parseEnum(
                candidate, BotEnvironment.class, "environment"));
    }

    private static Optional<InboxStatus> parseStatus(String value) {
        return optionalText(value, "status", 32).map(candidate -> parseEnum(candidate, InboxStatus.class, "status"));
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
