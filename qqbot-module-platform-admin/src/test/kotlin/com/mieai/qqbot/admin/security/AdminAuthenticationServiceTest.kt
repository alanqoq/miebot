package com.mieai.qqbot.admin.security

import com.mieai.qqbot.admin.onboarding.OnboardingSetupListener
import com.mieai.qqbot.persistence.admin.AdminUser
import com.mieai.qqbot.persistence.admin.AdminUserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminAuthenticationServiceTest {
    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun setupNormalizesUsernameHashesPasswordAndEstablishesAdminSession() {
        val repository = InMemoryAdminUserRepository()
        val encoder = BCryptPasswordEncoder(4)
        val strategyAuthentication = AtomicReference<Authentication>()
        val onboardingNotifications = AtomicInteger()
        val service = service(
            repository, encoder, Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { authentication, _, _ -> strategyAuthentication.set(authentication) },
            OnboardingSetupListener { onboardingNotifications.incrementAndGet() },
        )
        val request = request("192.0.2.10")

        val username = service.setup("  Admin.User  ", PASSWORD, request, MockHttpServletResponse())

        assertThat(username).isEqualTo("admin.user")
        val stored = requireNotNull(repository.user())
        assertThat(stored.username).isEqualTo("admin.user")
        assertThat(stored.passwordHash).startsWith("\$2").doesNotContain(PASSWORD)
        assertThat(encoder.matches(PASSWORD, stored.passwordHash)).isTrue()
        assertThat(stored.toString()).contains("passwordHash=<redacted>").doesNotContain(PASSWORD).doesNotContain(stored.passwordHash)
        assertAdminSession(request, "admin.user")
        assertThat(strategyAuthentication.get().authorities).extracting("authority").containsExactly("ROLE_ADMIN")
        assertThat(onboardingNotifications).hasValue(1)
    }

    @Test
    fun firstSetupIsAtomicWhenTwoRequestsRace() {
        val repository = RacingAdminUserRepository()
        val service = service(
            repository, DeterministicPasswordEncoder(), Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { _, _, _ -> },
        )
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = mutableListOf<Any>()

        try {
            val first = executor.submit(Callable { service.setup("first.admin", PASSWORD, request("192.0.2.11"), MockHttpServletResponse()) })
            val second = executor.submit(Callable { service.setup("second.admin", PASSWORD, request("192.0.2.12"), MockHttpServletResponse()) })
            outcomes += outcome(first)
            outcomes += outcome(second)
        } finally {
            executor.shutdownNow()
        }

        assertThat(outcomes).filteredOn(String::class.java::isInstance).hasSize(1)
        assertThat(outcomes).filteredOn(AdminAlreadyConfiguredException::class.java::isInstance).hasSize(1)
        assertThat(repository.insertedCount()).isEqualTo(1)
        assertThat(repository.user()).isNotNull()
    }

    @ParameterizedTest
    @MethodSource("invalidSetupPasswords")
    fun enforcesPasswordPolicy(username: String, password: String) {
        val repository = InMemoryAdminUserRepository()
        val service = service(
            repository, BCryptPasswordEncoder(4), Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { _, _, _ -> },
        )

        assertThatIllegalArgumentException().isThrownBy {
            service.setup(username, password, request("192.0.2.20"), MockHttpServletResponse())
        }
        assertThat(repository.user()).isNull()
    }

    @Test
    fun acceptsPasswordAtTheSeventyTwoUtf8ByteBoundary() {
        val repository = InMemoryAdminUserRepository()
        val service = service(
            repository, BCryptPasswordEncoder(4), Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { _, _, _ -> },
        )
        val password = "汉".repeat(24)

        service.setup("boundary.admin", password, request("192.0.2.21"), MockHttpServletResponse())

        assertThat(repository.user()).isNotNull()
    }

    @Test
    fun unknownUsernameStillPerformsDummyBcryptMatch() {
        val repository = InMemoryAdminUserRepository()
        val encoder = RecordingBcryptPasswordEncoder()
        val service = service(
            repository, encoder, Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { _, _, _ -> },
        )

        assertThatThrownBy {
            service.login("  Missing.User  ", PASSWORD, request("198.51.100.1"), MockHttpServletResponse())
        }.isInstanceOf(InvalidAdminCredentialsException::class.java)

        assertThat(repository.lastLookup()).isEqualTo("missing.user")
        assertThat(encoder.matchedHashes()).singleElement().asString().startsWith("\$2")
    }

    @Test
    fun successfulLoginCreatesRoleAdminSessionAndLogoutInvalidatesIt() {
        val repository = InMemoryAdminUserRepository()
        val encoder = BCryptPasswordEncoder(4)
        repository.insertFirst(user("admin.user", encoder.encode(PASSWORD)))
        val strategyAuthentication = AtomicReference<Authentication>()
        val service = service(
            repository, encoder, Clock.fixed(NOW, ZoneOffset.UTC),
            SessionAuthenticationStrategy { authentication, _, _ -> strategyAuthentication.set(authentication) },
        )
        val request = request("198.51.100.2")
        val response = MockHttpServletResponse()

        val username = service.login(" ADMIN.USER ", PASSWORD, request, response)

        assertThat(username).isEqualTo("admin.user")
        val authentication = assertAdminSession(request, "admin.user")
        assertThat(authentication.credentials).isNull()
        assertThat(strategyAuthentication.get()).isSameAs(authentication)
        val session = request.getSession(false) as MockHttpSession

        service.logout(request, response)

        assertThat(session.isInvalid).isTrue()
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }

    @Test
    fun changesPasswordOnlyAfterVerifyingTheCurrentPassword() {
        val repository = InMemoryAdminUserRepository()
        val encoder = BCryptPasswordEncoder(4)
        repository.insertFirst(user("admin.user", encoder.encode(PASSWORD)))
        val service = service(
            repository, encoder, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC),
            SessionAuthenticationStrategy { _, _, _ -> },
        )

        assertThatThrownBy { service.changePassword("admin.user", "incorrect-password", "new-correct-password") }
            .isInstanceOf(InvalidCurrentPasswordException::class.java)
        service.changePassword("admin.user", PASSWORD, "new-correct-password")

        val updated = requireNotNull(repository.user())
        assertThat(encoder.matches("new-correct-password", updated.passwordHash)).isTrue()
        assertThat(updated.updatedAt).isEqualTo(NOW.plusSeconds(5))
    }

    @Test
    fun fifthConsecutiveFailureBlocksThePairForFifteenMinutes() {
        val repository = InMemoryAdminUserRepository()
        val clock = MutableClock(NOW)
        val service = service(
            repository, BCryptPasswordEncoder(4), clock, SessionAuthenticationStrategy { _, _, _ -> },
        )
        val request = request("203.0.113.5")

        repeat(4) {
            assertThatThrownBy {
                service.login("missing.admin", PASSWORD, request, MockHttpServletResponse())
            }.isInstanceOf(InvalidAdminCredentialsException::class.java)
        }

        assertThatThrownBy {
            service.login("missing.admin", PASSWORD, request, MockHttpServletResponse())
        }.isInstanceOfSatisfying(LoginThrottledException::class.java) { exception ->
            assertThat(exception.retryAfterSeconds).isEqualTo(900L)
        }
        assertThatThrownBy {
            service.login("missing.admin", PASSWORD, request, MockHttpServletResponse())
        }.isInstanceOf(LoginThrottledException::class.java)

        clock.advance(Duration.ofMinutes(15))
        assertThatThrownBy {
            service.login("missing.admin", PASSWORD, request, MockHttpServletResponse())
        }.isInstanceOf(InvalidAdminCredentialsException::class.java)
    }

    private fun service(
        repository: AdminUserRepository,
        encoder: PasswordEncoder,
        clock: Clock,
        sessionStrategy: SessionAuthenticationStrategy,
        onboardingSetupListener: OnboardingSetupListener = OnboardingSetupListener {},
    ) = AdminAuthenticationService(
        repository,
        encoder,
        HttpSessionSecurityContextRepository(),
        sessionStrategy,
        AdminLoginAttemptGuard(clock),
        onboardingSetupListener,
        clock,
    )

    private fun request(remoteAddress: String) = MockHttpServletRequest().apply { remoteAddr = remoteAddress }

    private fun assertAdminSession(request: MockHttpServletRequest, expectedUsername: String): Authentication {
        val session = request.getSession(false) as MockHttpSession
        assertThat(session).isNotNull()
        val context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) as SecurityContext
        assertThat(context).isNotNull()
        val authentication = context.authentication
        assertThat(authentication.name).isEqualTo(expectedUsername)
        assertThat(authentication.isAuthenticated).isTrue()
        assertThat(authentication.authorities).extracting("authority").containsExactly("ROLE_ADMIN")
        return authentication
    }

    private fun user(username: String, passwordHash: String) = AdminUser(UUID.randomUUID(), username, passwordHash, true, NOW, NOW)

    private fun outcome(future: Future<String>): Any = try {
        future.get()
    } catch (exception: ExecutionException) {
        requireNotNull(exception.cause)
    }

    private open class InMemoryAdminUserRepository : AdminUserRepository {
        private val userValue = AtomicReference<AdminUser>()
        private val lastLookupValue = AtomicReference<String>()

        override fun exists(): Boolean = userValue.get() != null

        override fun findByUsername(username: String): AdminUser? {
            lastLookupValue.set(username)
            val current = userValue.get()
            return current?.takeIf { it.username == username }
        }

        override fun insertFirst(user: AdminUser): Boolean = userValue.compareAndSet(null, user)

        override fun updatePassword(id: UUID, passwordHash: String, updatedAt: Instant): Boolean {
            val current = userValue.get() ?: return false
            if (current.id != id) return false
            return userValue.compareAndSet(
                current,
                AdminUser(current.id, current.username, passwordHash, current.enabled, current.createdAt, updatedAt),
            )
        }

        fun user(): AdminUser? = userValue.get()
        fun lastLookup(): String? = lastLookupValue.get()
    }

    private class RacingAdminUserRepository : InMemoryAdminUserRepository() {
        private val initialExistsBarrier = CyclicBarrier(2)
        private val existsCalls = AtomicInteger()
        private val insertedCountValue = AtomicInteger()

        override fun exists(): Boolean {
            if (existsCalls.incrementAndGet() <= 2) {
                try {
                    initialExistsBarrier.await()
                } catch (exception: Exception) {
                    throw IllegalStateException("setup race was interrupted", exception)
                }
            }
            return super.exists()
        }

        override fun insertFirst(user: AdminUser): Boolean = super.insertFirst(user).also { inserted ->
            if (inserted) insertedCountValue.incrementAndGet()
        }

        fun insertedCount(): Int = insertedCountValue.get()
    }

    private class DeterministicPasswordEncoder : PasswordEncoder {
        override fun encode(rawPassword: CharSequence): String = "{test}" + rawPassword.toString().hashCode().toUInt().toString(16)
        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean = encode(rawPassword) == encodedPassword
    }

    private class RecordingBcryptPasswordEncoder : PasswordEncoder {
        private val delegate: PasswordEncoder = BCryptPasswordEncoder(4)
        private val matchedHashesValue = mutableListOf<String>()
        override fun encode(rawPassword: CharSequence): String = delegate.encode(rawPassword)
        override fun matches(rawPassword: CharSequence, encodedPassword: String): Boolean {
            matchedHashesValue += encodedPassword
            return delegate.matches(rawPassword, encodedPassword)
        }

        fun matchedHashes(): List<String> = matchedHashesValue.toList()
    }

    private class MutableClock(private var instantValue: Instant) : Clock() {
        fun advance(duration: Duration) { instantValue = instantValue.plus(duration) }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = if (zone == ZoneOffset.UTC) this else Clock.fixed(instantValue, zone)
        override fun instant(): Instant = instantValue
    }

    fun invalidSetupPasswords(): Stream<Arguments> = Stream.of(
        Arguments.of("admin.user", "too-short"),
        Arguments.of("administrator", "ADMINISTRATOR"),
        Arguments.of("admin.user", " ".repeat(12)),
        Arguments.of("admin.user", "a".repeat(73)),
        Arguments.of("admin.user", "汉".repeat(25)),
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-07-17T00:00:00Z")
        const val PASSWORD = "correct-horse-battery-staple"
    }
}
