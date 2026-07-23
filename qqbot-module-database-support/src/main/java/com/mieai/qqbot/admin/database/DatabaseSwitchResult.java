package com.mieai.qqbot.admin.database;

public record DatabaseSwitchResult(
        DatabaseConfigurationView configuration,
        DatabaseTestResult verification) {
}
