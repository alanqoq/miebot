package com.mieai.qqbot.persistence.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcAdminUserRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    private AdminUserRepository repository;

    @BeforeEach
    void setUp() {
        DataSource dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("admin.db"));
        SQLiteDatabaseInitializer.migrate(dataSource);
        repository = new JdbcAdminUserRepository(dataSource);
    }

    @Test
    void atomicallyAllowsOnlyTheFirstAdministrator() {
        AdminUser first = user("admin", "$2a$12$first");
        AdminUser second = user("other", "$2a$12$second");

        assertThat(repository.exists()).isFalse();
        assertThat(repository.insertFirst(first)).isTrue();
        assertThat(repository.insertFirst(second)).isFalse();

        assertThat(repository.exists()).isTrue();
        AdminUser persisted = repository.findByUsername("ADMIN").orElseThrow();
        assertThat(persisted.id()).isEqualTo(first.id());
        assertThat(persisted.username()).isEqualTo(first.username());
        assertThat(persisted.passwordHash()).isEqualTo(first.passwordHash());
        assertThat(persisted.enabled()).isTrue();
        assertThat(persisted.createdAt()).isEqualTo(NOW);
        assertThat(repository.findByUsername("other")).isEmpty();
        assertThat(first.toString()).contains("<redacted>").doesNotContain(first.passwordHash());
    }

    private static AdminUser user(String username, String passwordHash) {
        return new AdminUser(UUID.randomUUID(), username, passwordHash, true, NOW, NOW);
    }
}
