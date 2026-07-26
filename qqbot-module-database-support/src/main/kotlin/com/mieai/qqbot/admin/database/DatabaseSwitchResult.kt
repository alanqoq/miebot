package com.mieai.qqbot.admin.database


data class DatabaseSwitchResult(
    val configuration: DatabaseConfigurationView,
    val verification: DatabaseTestResult,
)
