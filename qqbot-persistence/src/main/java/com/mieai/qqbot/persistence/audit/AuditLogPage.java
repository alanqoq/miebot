package com.mieai.qqbot.persistence.audit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record AuditLogPage(List<AuditLog> logs, Optional<String> nextCursor) {
    public AuditLogPage {
        logs = List.copyOf(Objects.requireNonNull(logs, "logs must not be null"));
        nextCursor = Objects.requireNonNull(nextCursor, "nextCursor must not be null");
    }
}
