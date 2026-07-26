package com.mieai.qqbot.modules.database

/** Coordinates one module with an atomic active-database replacement. */
interface DatabaseTransitionParticipant {
    fun beforeOrder(): Int = 0

    fun afterOrder(): Int = 0

    fun beforeDatabaseChange()

    fun afterDatabaseChange()
}
