package com.mieai.qqbot.admin.health;

import java.time.Instant;
import java.util.Map;

public record HealthStatus(String status, Instant checkedAt, Map<String, String> components) {
}
