package com.mieai.qqbot.admin.events;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.inbox.EventInboxRepository;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.BotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.PluginDelivery;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryPage;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryQuery;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryRepository;
import com.mieai.qqbot.persistence.plugin.PluginDeliveryStatus;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** Read-only management projection for plugin attempts and plugin dead letters. */
@Service
public class PluginDeliveryAdministrationService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_ERROR_CHARACTERS = 1024;

    private final PluginDeliveryRepository deliveries;
    private final BotPluginBindingRepository bindings;
    private final BotRepository bots;
    private final EventInboxRepository inbox;

    public PluginDeliveryAdministrationService(
            PluginDeliveryRepository deliveries,
            BotPluginBindingRepository bindings,
            BotRepository bots,
            EventInboxRepository inbox) {
        this.deliveries = Objects.requireNonNull(deliveries, "deliveries must not be null");
        this.bindings = Objects.requireNonNull(bindings, "bindings must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.inbox = Objects.requireNonNull(inbox, "inbox must not be null");
    }

    public PluginDeliveryPageResponse list(
            String limit, String cursor, String query, String bindingId, String status,
            boolean deadLetterOnly) {
        Optional<PluginDeliveryStatus> parsedStatus = deadLetterOnly
                ? parseDeadLetterStatus(status)
                : parseStatus(status);
        PluginDeliveryQuery request = new PluginDeliveryQuery(
                parseLimit(limit),
                optionalText(cursor, "cursor", 512, false),
                optionalUuid(bindingId, "bindingId"),
                deadLetterOnly ? Optional.of(PluginDeliveryStatus.DEAD_LETTER) : parsedStatus,
                optionalText(query, "query", 256, true));
        PluginDeliveryPage page = deliveries.query(request);
        Projection projection = projection();
        return new PluginDeliveryPageResponse(
                page.deliveries().stream().map(delivery -> summary(delivery, projection)).toList(),
                page.nextCursor().orElse(null),
                page.nextCursor().isPresent(),
                Instant.now(),
                PluginDeliveryQueueStatsResponse.from(deliveries.statistics()));
    }

    public PluginDeliveryDetailResponse get(String id, boolean deadLetterOnly) {
        UUID parsedId = parseUuid(id, "id");
        PluginDelivery delivery = deliveries.findById(parsedId)
                .filter(candidate -> !deadLetterOnly
                        || candidate.status() == PluginDeliveryStatus.DEAD_LETTER)
                .orElseThrow(() -> new NoSuchElementException(
                        "Plugin delivery was not found: " + id));
        BotPluginBinding binding = bindings.findById(delivery.bindingId())
                .orElseThrow(() -> new NoSuchElementException("Plugin binding was not found"));
        var event = inbox.findById(delivery.eventId())
                .orElseThrow(() -> new NoSuchElementException("Inbox event was not found"));
        String botName = bots.findById(binding.botId())
                .map(stored -> stored.definition().displayName()).orElse(null);
        return new PluginDeliveryDetailResponse(
                delivery.id(), delivery.eventId(), delivery.bindingId(), binding.pluginId(),
                binding.botId().value(), botName, delivery.handlerId(), delivery.status().name(),
                delivery.attempt(), delivery.availableAt(), delivery.leaseUntil().orElse(null),
                delivery.createdAt(), delivery.updatedAt(), delivery.completedAt().orElse(null),
                limitError(delivery.lastError().orElse(null)), event.eventType(),
                event.platformEventId(), event.receivedAt());
    }

    public PluginDeliveryQueueStatsResponse statistics() {
        return PluginDeliveryQueueStatsResponse.from(deliveries.statistics());
    }

    private Projection projection() {
        Map<UUID, BotPluginBinding> bindingMap = bindings.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(BotPluginBinding::id, Function.identity()));
        Map<BotId, String> botNames = bots.findAll().stream().collect(Collectors.toUnmodifiableMap(
                stored -> stored.id(), stored -> stored.definition().displayName()));
        return new Projection(bindingMap, botNames);
    }

    private static PluginDeliverySummaryResponse summary(
            PluginDelivery delivery, Projection projection) {
        BotPluginBinding binding = projection.bindings().get(delivery.bindingId());
        return new PluginDeliverySummaryResponse(
                delivery.id(), delivery.eventId(), delivery.bindingId(),
                binding == null ? null : binding.pluginId(),
                binding == null ? null : binding.botId().value(),
                binding == null ? null : projection.botNames().get(binding.botId()),
                delivery.handlerId(), delivery.status().name(), delivery.attempt(),
                delivery.availableAt(), delivery.createdAt(), delivery.updatedAt(),
                delivery.completedAt().orElse(null), limitError(delivery.lastError().orElse(null)));
    }

    private static int parseLimit(String value) {
        String candidate = value == null || value.isBlank()
                ? Integer.toString(DEFAULT_LIMIT) : value.strip();
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

    private static Optional<String> optionalText(
            String value, String name, int maximumLength, boolean allowWhitespace) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.strip();
        if (normalized.length() > maximumLength
                || normalized.codePoints().anyMatch(Character::isISOControl)
                || (!allowWhitespace && normalized.codePoints().anyMatch(Character::isWhitespace))) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return Optional.of(normalized);
    }

    private static Optional<UUID> optionalUuid(String value, String name) {
        return optionalText(value, name, 64, false).map(candidate -> parseUuid(candidate, name));
    }

    private static Optional<PluginDeliveryStatus> parseStatus(String value) {
        return optionalText(value, "status", 32, false).map(candidate -> {
            try {
                return PluginDeliveryStatus.valueOf(candidate.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("status is not supported: " + candidate, exception);
            }
        });
    }

    private static Optional<PluginDeliveryStatus> parseDeadLetterStatus(String value) {
        Optional<PluginDeliveryStatus> status = parseStatus(value);
        if (status.isPresent() && status.orElseThrow() != PluginDeliveryStatus.DEAD_LETTER) {
            throw new IllegalArgumentException("status must be DEAD_LETTER for the plugin DLQ view");
        }
        return status;
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

    private static String limitError(String value) {
        if (value == null || value.length() <= MAX_ERROR_CHARACTERS) return value;
        return value.substring(0, MAX_ERROR_CHARACTERS);
    }

    private record Projection(Map<UUID, BotPluginBinding> bindings, Map<BotId, String> botNames) {}
}
