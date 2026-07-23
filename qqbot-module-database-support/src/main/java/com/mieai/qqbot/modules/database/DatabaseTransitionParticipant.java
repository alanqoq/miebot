package com.mieai.qqbot.modules.database;

/** Coordinates one module with an atomic active-database replacement. */
public interface DatabaseTransitionParticipant {
    default int beforeOrder() { return 0; }
    default int afterOrder() { return 0; }
    void beforeDatabaseChange();
    void afterDatabaseChange();
}
