package com.mieai.qqbot.persistence.admin

import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class JdbcAdminUserRepositoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var repository: AdminUserRepository

    @BeforeEach
    fun setUp() {
        val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("admin.db"))
        SQLiteDatabaseInitializer.migrate(dataSource)
        repository = JdbcAdminUserRepository(dataSource)
    }

    @Test
    fun atomicallyAllowsOnlyTheFirstAdministrator() {
        val first = user("admin", "\$2a\$12\$first")
        val second = user("other", "\$2a\$12\$second")

        assertThat(repository.exists()).isFalse()
        assertThat(repository.insertFirst(first)).isTrue()
        assertThat(repository.insertFirst(second)).isFalse()

        assertThat(repository.exists()).isTrue()
        val persisted = requireNotNull(repository.findByUsername("ADMIN"))
        assertThat(persisted.id).isEqualTo(first.id)
        assertThat(persisted.username).isEqualTo(first.username)
        assertThat(persisted.passwordHash).isEqualTo(first.passwordHash)
        assertThat(persisted.enabled).isTrue()
        assertThat(persisted.createdAt).isEqualTo(NOW)
        assertThat(repository.findByUsername("other")).isNull()
        assertThat(first.toString()).contains("<redacted>").doesNotContain(first.passwordHash)
    }

    private fun user(username: String, passwordHash: String) =
        AdminUser(UUID.randomUUID(), username, passwordHash, true, NOW, NOW)

    companion object {
        private val NOW = Instant.parse("2026-07-16T12:00:00Z")
    }
}
