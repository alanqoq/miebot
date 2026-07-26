package com.mieai.qqbot.persistence.admin

import java.time.Instant
import java.util.UUID

interface AdminUserRepository {
    fun exists(): Boolean

    fun findByUsername(username: String): AdminUser?

    /** Atomically inserts the only phase-one administrator. */
    fun insertFirst(user: AdminUser): Boolean

    /** Replaces only the password hash for an existing administrator. */
    fun updatePassword(id: UUID, passwordHash: String, updatedAt: Instant): Boolean
}
