package com.mieai.qqbot.admin.onboarding;

import com.mieai.qqbot.admin.database.DatabaseType;

public interface OnboardingAdministrationService extends OnboardingSetupListener {
    OnboardingStatusResponse status();

    OnboardingStatusResponse databaseConfigured(long expectedRevision, DatabaseType databaseType);

    OnboardingStatusResponse complete();
}
