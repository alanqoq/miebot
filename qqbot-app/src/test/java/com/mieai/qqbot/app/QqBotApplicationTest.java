package com.mieai.qqbot.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.app.onboarding.OnboardingProperties;
import com.mieai.qqbot.module.host.FrameworkModuleHost;
import com.mieai.qqbot.module.host.ModuleRuntimeState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class QqBotApplicationTest {

    private static final Path DATABASE_FILE = createDatabasePath();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("qqbot.database.sqlite.path", () -> DATABASE_FILE);
        registry.add(
                "qqbot.onboarding.state-file",
                () -> DATABASE_FILE.resolveSibling(DATABASE_FILE.getFileName() + ".onboarding.json"));
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OnboardingProperties onboardingProperties;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FrameworkModuleHost moduleHost;

    @Test
    void startsWithSQLiteAndWebApplicationContext() throws Exception {
        assertThat(context).isNotNull();
        assertThat(DATABASE_FILE).exists();
        assertThat(Path.of(OnboardingProperties.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).getFileName().toString())
                .isEqualTo("qqbot-module-platform-admin-0.3.0.jar");
        assertThat(onboardingProperties.getStateFile())
                .isEqualTo(DATABASE_FILE.resolveSibling(DATABASE_FILE.getFileName() + ".onboarding.json"));
    }

    @Test
    void activatesAllBuiltInFrameworkModules() {
        assertThat(moduleHost.snapshots())
                .extracting(snapshot -> snapshot.descriptor().id())
                .containsExactly(
                        "cluster-support",
                        "database-support",
                        "onebot11",
                        "operations",
                        "platform-admin",
                        "plugin-support",
                        "qqbot-runtime");
        assertThat(moduleHost.snapshots())
                .allSatisfy(snapshot -> assertThat(snapshot.state())
                        .as(snapshot.descriptor().id())
                        .isEqualTo(ModuleRuntimeState.ACTIVE));
        assertThat(moduleHost.snapshots())
                .allSatisfy(snapshot -> assertThat(moduleHost.artifact(snapshot.descriptor().id()))
                        .isPresent()
                        .get()
                        .satisfies(artifact -> {
                            assertThat(artifact.path().getParent().getFileName().toString())
                                    .isEqualTo("test-modules");
                            assertThat(artifact.sha256()).matches("[0-9a-f]{64}");
                        }));
        assertThat(moduleHost.findWebAsset("operations", "main.js")).isPresent();
    }

    @Test
    void servesSetupAsAnUnauthenticatedAngularRoute() throws Exception {
        mockMvc.perform(get("/setup"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void servesLoginAsAnUnauthenticatedAngularRoute() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void configuresRequiredSQLitePragmas() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            assertThat(pragmaText(statement, "journal_mode")).isEqualToIgnoringCase("wal");
            assertThat(pragmaInt(statement, "foreign_keys")).isEqualTo(1);
            assertThat(pragmaInt(statement, "busy_timeout")).isEqualTo(5000);
        }
    }

    private static String pragmaText(Statement statement, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static int pragmaInt(Statement statement, String name) throws Exception {
        try (ResultSet result = statement.executeQuery("PRAGMA " + name)) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
    }

    private static Path createDatabasePath() {
        Path directory = Path.of("build", "test-databases").toAbsolutePath();
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
        return directory.resolve("context-" + UUID.randomUUID() + ".db");
    }
}
