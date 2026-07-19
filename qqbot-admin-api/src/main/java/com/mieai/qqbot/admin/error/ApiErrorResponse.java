package com.mieai.qqbot.admin.error;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, String> fieldErrors,
        String traceId,
        Instant timestamp) {
}
