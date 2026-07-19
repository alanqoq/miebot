package com.mieai.qqbot.persistence.audit;

import java.util.Optional;

public interface AuditLogRepository {
    void append(AuditLog log);
    AuditLogPage query(int limit, Optional<String> cursor, Optional<String> actor, Optional<String> action);
}
