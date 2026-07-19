package com.mieai.qqbot.persistence.admin;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository {
    boolean exists();

    Optional<AdminUser> findByUsername(String username);

    /** Atomically inserts the only phase-one administrator. */
    boolean insertFirst(AdminUser user);

    /** Replaces only the password hash for an existing administrator. */
    default boolean updatePassword(UUID id, String passwordHash, Instant updatedAt) {
        throw new UnsupportedOperationException("password updates are not supported by this repository");
    }
}
