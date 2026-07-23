package com.mieai.qqbot.app.onboarding;

import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.admin.onboarding.OnboardingStage;
import java.time.Instant;
import java.util.Objects;

public record OnboardingFileState(
        int version,
        OnboardingStage stage,
        DatabaseType databaseType,
        Instant completedAt) {
    static final int CURRENT_VERSION = 1;

    public OnboardingFileState {
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported onboarding state version: " + version);
        }
        Objects.requireNonNull(stage, "onboarding stage must not be null");
        switch (stage) {
            case ADMIN, DATABASE -> {
                if (databaseType != null || completedAt != null) {
                    throw new IllegalArgumentException(stage + " onboarding state cannot contain completion data");
                }
            }
            case BOT -> {
                Objects.requireNonNull(databaseType, "BOT onboarding state requires a database type");
                if (completedAt != null) {
                    throw new IllegalArgumentException("BOT onboarding state cannot contain completedAt");
                }
            }
            case COMPLETE -> {
                Objects.requireNonNull(databaseType, "COMPLETE onboarding state requires a database type");
                Objects.requireNonNull(completedAt, "COMPLETE onboarding state requires completedAt");
            }
        }
    }

    static OnboardingFileState admin() {
        return new OnboardingFileState(CURRENT_VERSION, OnboardingStage.ADMIN, null, null);
    }

    static OnboardingFileState database() {
        return new OnboardingFileState(CURRENT_VERSION, OnboardingStage.DATABASE, null, null);
    }

    static OnboardingFileState bot(DatabaseType databaseType) {
        return new OnboardingFileState(CURRENT_VERSION, OnboardingStage.BOT, databaseType, null);
    }

    static OnboardingFileState complete(DatabaseType databaseType, Instant completedAt) {
        return new OnboardingFileState(
                CURRENT_VERSION,
                OnboardingStage.COMPLETE,
                databaseType,
                completedAt);
    }
}
