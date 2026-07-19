package com.mieai.qqbot.admin.system;

import java.time.Instant;

public record SystemInfoResponse(
        String service,
        String version,
        String status,
        String database,
        Instant serverTime) {
}
