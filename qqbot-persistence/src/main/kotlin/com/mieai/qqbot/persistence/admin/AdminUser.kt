package com.mieai.qqbot.persistence.admin

import java.time.Instant
import java.util.UUID

/** Persisted administrator identity. The password hash is redacted from diagnostics. */
class AdminUser(
    id: UUID,
    username: String,
    passwordHash: String,
    enabled: Boolean,
    createdAt: Instant,
    updatedAt: Instant,
) {
    val id = id
    val username = requireText(username, "username")
    val passwordHash = requireText(passwordHash, "passwordHash")
    val enabled = enabled
    val createdAt = createdAt
    val updatedAt = updatedAt

    init {
        require(!updatedAt.isBefore(createdAt)) { "updatedAt must not precede createdAt" }
    }

    override fun toString(): String =
        "AdminUser[id=$id, username=$username, passwordHash=<redacted>, enabled=$enabled, " +
            "createdAt=$createdAt, updatedAt=$updatedAt]"

    private fun requireText(value: String, name: String): String {
        require(value.isNotBlank()) { "$name must not be blank" }
        return value
    }
}
