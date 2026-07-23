package com.mieai.qqbot.admin.database;

import jakarta.validation.constraints.Positive;

public record ReloadDatabaseRequest(@Positive long expectedRevision) {
}
