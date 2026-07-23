package com.mieai.qqbot.admin.database;

public interface DatabaseAdministrationService {
    DatabaseConfigurationView current();

    DatabaseTestResult test(DatabaseSettings settings);

    DatabaseSwitchResult switchDatabase(
            long expectedRevision,
            DatabaseSettings settings,
            String currentAdminUsername);

    DatabaseSwitchResult reload(long expectedRevision, String currentAdminUsername);
}
