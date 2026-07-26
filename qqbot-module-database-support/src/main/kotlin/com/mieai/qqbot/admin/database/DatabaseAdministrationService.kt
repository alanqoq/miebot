package com.mieai.qqbot.admin.database

interface DatabaseAdministrationService {
    fun current(): DatabaseConfigurationView

    fun test(settings: DatabaseSettings): DatabaseTestResult

    fun switchDatabase(
        expectedRevision: Long,
        settings: DatabaseSettings,
        currentAdminUsername: String,
    ): DatabaseSwitchResult

    fun reload(expectedRevision: Long, currentAdminUsername: String): DatabaseSwitchResult
}
