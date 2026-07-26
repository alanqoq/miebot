package com.mieai.qqbot.app.onboarding

import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.admin.onboarding.OnboardingStage
import java.time.Instant

data class OnboardingFileState(
    val version: Int,
    val stage: OnboardingStage,
    val databaseType: DatabaseType?,
    val completedAt: Instant?,
) {
    init {
        require(version == CURRENT_VERSION) { "Unsupported onboarding state version: $version" }
        when (stage) {
            OnboardingStage.ADMIN,
            OnboardingStage.DATABASE,
            -> require(databaseType == null && completedAt == null) {
                "$stage onboarding state cannot contain completion data"
            }

            OnboardingStage.BOT -> {
                require(databaseType != null) { "BOT onboarding state requires a database type" }
                require(completedAt == null) { "BOT onboarding state cannot contain completedAt" }
            }

            OnboardingStage.COMPLETE -> {
                require(databaseType != null) { "COMPLETE onboarding state requires a database type" }
                require(completedAt != null) { "COMPLETE onboarding state requires completedAt" }
            }
        }
    }

    companion object {
        const val CURRENT_VERSION = 1

        fun admin(): OnboardingFileState =
            OnboardingFileState(CURRENT_VERSION, OnboardingStage.ADMIN, null, null)

        fun database(): OnboardingFileState =
            OnboardingFileState(CURRENT_VERSION, OnboardingStage.DATABASE, null, null)

        fun bot(databaseType: DatabaseType): OnboardingFileState =
            OnboardingFileState(CURRENT_VERSION, OnboardingStage.BOT, databaseType, null)

        fun complete(databaseType: DatabaseType, completedAt: Instant): OnboardingFileState =
            OnboardingFileState(CURRENT_VERSION, OnboardingStage.COMPLETE, databaseType, completedAt)
    }
}
