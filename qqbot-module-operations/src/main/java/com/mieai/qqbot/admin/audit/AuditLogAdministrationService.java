package com.mieai.qqbot.admin.audit;

import com.mieai.qqbot.persistence.audit.AuditLog;
import com.mieai.qqbot.persistence.audit.AuditLogPage;
import com.mieai.qqbot.persistence.audit.AuditLogRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditLogAdministrationService {
    private final AuditLogRepository repository;

    public AuditLogAdministrationService(AuditLogRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    public void append(String actor, String action, String path, int status,
            String remoteAddress, String traceId) {
        repository.append(new AuditLog(UUID.randomUUID(), optional(actor, 64),
                require(action, 16), require(path, 512), status, optional(remoteAddress, 128),
                optional(traceId, 128), Instant.now()));
    }

    public AuditLogPageResponse list(String limit, String cursor, String actor, String action) {
        int parsedLimit = parseLimit(limit);
        Optional<String> parsedCursor = text(cursor, "cursor", 512);
        Optional<String> parsedActor = text(actor, "actor", 64);
        Optional<String> parsedAction = text(action, "action", 16);
        AuditLogPage page = repository.query(parsedLimit, parsedCursor, parsedActor, parsedAction);
        return new AuditLogPageResponse(page.logs().stream().map(AuditLogResponse::from).toList(),
                page.nextCursor().orElse(null), page.nextCursor().isPresent(), Instant.now());
    }

    private static int parseLimit(String value) {
        String candidate = value == null || value.isBlank() ? "50" : value.strip();
        try {
            int parsed = Integer.parseInt(candidate);
            if (parsed < 1 || parsed > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("limit must be an integer", exception);
        }
    }

    private static Optional<String> text(String value, String name, int max) {
        if (value == null || value.isBlank()) return Optional.empty();
        return Optional.of(require(value.strip(), max, name));
    }

    private static Optional<String> optional(String value, int max) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(require(value, max, "value"));
    }

    private static String require(String value, int max) {
        return require(value, max, "value");
    }

    private static String require(String value, int max, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
