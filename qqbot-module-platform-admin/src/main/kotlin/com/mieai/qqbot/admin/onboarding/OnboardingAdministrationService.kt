package com.mieai.qqbot.admin.onboarding

import com.mieai.qqbot.admin.database.DatabaseType

interface OnboardingAdministrationService : OnboardingSetupListener {
    fun status(): OnboardingStatusResponse

    fun databaseConfigured(expectedRevision: Long, databaseType: DatabaseType): OnboardingStatusResponse

    fun complete(): OnboardingStatusResponse
}
