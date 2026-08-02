package com.mieai.qqbot.app

import com.mieai.qqbot.app.onboarding.OnboardingProperties
import com.mieai.qqbot.module.host.FrameworkModuleHost
import com.mieai.qqbot.module.host.ModuleRuntimeState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Statement
import java.util.UUID
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class QqBotApplicationTest {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var onboardingProperties: OnboardingProperties

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var moduleHost: FrameworkModuleHost

    @Test
    fun startsWithSQLiteAndWebApplicationContext() {
        assertThat(context).isNotNull()
        assertThat(DATABASE_FILE).exists()
        assertThat(
            Path.of(OnboardingProperties::class.java.protectionDomain.codeSource.location.toURI())
                .fileName
                .toString(),
        ).isEqualTo("qqbot-module-platform-admin-1.0.4.jar")
        assertThat(onboardingProperties.stateFile)
            .isEqualTo(DATABASE_FILE.resolveSibling("${DATABASE_FILE.fileName}.onboarding.json"))
    }

    @Test
    fun activatesAllBuiltInFrameworkModules() {
        assertThat(moduleHost.snapshots().map { it.descriptor.id })
            .containsExactly(
                "cluster-support",
                "database-support",
                "onebot11",
                "operations",
                "platform-admin",
                "plugin-support",
                "qqbot-runtime",
            )
        moduleHost.snapshots().forEach { snapshot ->
            assertThat(snapshot.state)
                .`as`(snapshot.descriptor.id)
                .isEqualTo(ModuleRuntimeState.ACTIVE)
        }
        moduleHost.snapshots().forEach { snapshot ->
            val artifact = requireNotNull(moduleHost.artifact(snapshot.descriptor.id))
            assertThat(artifact.path.parent.fileName.toString()).isEqualTo("test-modules")
            assertThat(artifact.sha256).matches("[0-9a-f]{64}")
        }
        assertThat(moduleHost.findWebAsset("operations", "main.js")).isNotNull()
    }

    @Test
    fun servesSetupAsAnUnauthenticatedAngularRoute() {
        mockMvc.perform(get("/setup"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun servesLoginAsAnUnauthenticatedAngularRoute() {
        mockMvc.perform(get("/login"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/index.html"))
    }

    @Test
    fun configuresRequiredSQLitePragmas() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                assertThat(pragmaText(statement, "journal_mode")).isEqualToIgnoringCase("wal")
                assertThat(pragmaInt(statement, "foreign_keys")).isEqualTo(1)
                assertThat(pragmaInt(statement, "busy_timeout")).isEqualTo(5000)
            }
        }
    }

    private fun pragmaText(statement: Statement, name: String): String =
        statement.executeQuery("PRAGMA $name").use { result ->
            assertThat(result.next()).isTrue()
            result.getString(1)
        }

    private fun pragmaInt(statement: Statement, name: String): Int =
        statement.executeQuery("PRAGMA $name").use { result ->
            assertThat(result.next()).isTrue()
            result.getInt(1)
        }

    companion object {
        private val DATABASE_FILE: Path = createDatabasePath()

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("qqbot.database.sqlite.path") { DATABASE_FILE }
            registry.add("qqbot.onboarding.state-file") {
                DATABASE_FILE.resolveSibling("${DATABASE_FILE.fileName}.onboarding.json")
            }
        }

        private fun createDatabasePath(): Path {
            val directory = Path.of("build", "test-databases").toAbsolutePath()
            try {
                Files.createDirectories(directory)
            } catch (exception: Exception) {
                throw ExceptionInInitializerError(exception)
            }
            return directory.resolve("context-${UUID.randomUUID()}.db")
        }
    }
}
