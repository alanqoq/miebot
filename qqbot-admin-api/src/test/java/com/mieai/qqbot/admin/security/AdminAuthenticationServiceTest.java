package com.mieai.qqbot.admin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mieai.qqbot.persistence.admin.AdminUser;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import com.mieai.qqbot.admin.onboarding.OnboardingSetupListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class AdminAuthenticationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
    private static final String PASSWORD = "correct-horse-battery-staple";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void setupNormalizesUsernameHashesPasswordAndEstablishesAdminSession() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        AtomicReference<Authentication> strategyAuthentication = new AtomicReference<>();
        AtomicInteger onboardingNotifications = new AtomicInteger();
        AdminAuthenticationService service = service(
                repository,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> strategyAuthentication.set(authentication),
                onboardingNotifications::incrementAndGet);
        MockHttpServletRequest request = request("192.0.2.10");

        String username = service.setup(
                "  Admin.User  ", PASSWORD, request, new MockHttpServletResponse());

        assertThat(username).isEqualTo("admin.user");
        AdminUser stored = repository.user().orElseThrow();
        assertThat(stored.username()).isEqualTo("admin.user");
        assertThat(stored.passwordHash()).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(encoder.matches(PASSWORD, stored.passwordHash())).isTrue();
        assertThat(stored.toString())
                .contains("passwordHash=<redacted>")
                .doesNotContain(PASSWORD)
                .doesNotContain(stored.passwordHash());
        assertAdminSession(request, "admin.user");
        assertThat(strategyAuthentication.get().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
        assertThat(onboardingNotifications).hasValue(1);
    }

    @Test
    void firstSetupIsAtomicWhenTwoRequestsRace() throws Exception {
        RacingAdminUserRepository repository = new RacingAdminUserRepository();
        AdminAuthenticationService service = service(
                repository,
                new DeterministicPasswordEncoder(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> {});
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();

        try {
            Future<String> first = executor.submit(() -> service.setup(
                    "first.admin", PASSWORD, request("192.0.2.11"), new MockHttpServletResponse()));
            Future<String> second = executor.submit(() -> service.setup(
                    "second.admin", PASSWORD, request("192.0.2.12"), new MockHttpServletResponse()));
            outcomes.add(outcome(first));
            outcomes.add(outcome(second));
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).filteredOn(String.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(AdminAlreadyConfiguredException.class::isInstance).hasSize(1);
        assertThat(repository.insertedCount()).isEqualTo(1);
        assertThat(repository.user()).isPresent();
    }

    @ParameterizedTest
    @MethodSource("invalidSetupPasswords")
    void enforcesPasswordPolicy(String username, String password) {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        AdminAuthenticationService service = service(
                repository,
                new BCryptPasswordEncoder(4),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> {});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.setup(
                        username, password, request("192.0.2.20"), new MockHttpServletResponse()));
        assertThat(repository.user()).isEmpty();
    }

    @Test
    void acceptsPasswordAtTheSeventyTwoUtf8ByteBoundary() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        AdminAuthenticationService service = service(
                repository,
                new BCryptPasswordEncoder(4),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> {});
        String password = "汉".repeat(24);

        service.setup("boundary.admin", password, request("192.0.2.21"), new MockHttpServletResponse());

        assertThat(repository.user()).isPresent();
    }

    @Test
    void unknownUsernameStillPerformsDummyBcryptMatch() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        RecordingBcryptPasswordEncoder encoder = new RecordingBcryptPasswordEncoder();
        AdminAuthenticationService service = service(
                repository,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> {});

        assertThatThrownBy(() -> service.login(
                        "  Missing.User  ", PASSWORD,
                        request("198.51.100.1"), new MockHttpServletResponse()))
                .isInstanceOf(InvalidAdminCredentialsException.class);

        assertThat(repository.lastLookup()).isEqualTo("missing.user");
        assertThat(encoder.matchedHashes()).singleElement().asString().startsWith("$2");
    }

    @Test
    void successfulLoginCreatesRoleAdminSessionAndLogoutInvalidatesIt() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        repository.insertFirst(user("admin.user", encoder.encode(PASSWORD)));
        AtomicReference<Authentication> strategyAuthentication = new AtomicReference<>();
        AdminAuthenticationService service = service(
                repository,
                encoder,
                Clock.fixed(NOW, ZoneOffset.UTC),
                (authentication, request, response) -> strategyAuthentication.set(authentication));
        MockHttpServletRequest request = request("198.51.100.2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String username = service.login(" ADMIN.USER ", PASSWORD, request, response);

        assertThat(username).isEqualTo("admin.user");
        Authentication authentication = assertAdminSession(request, "admin.user");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(strategyAuthentication.get()).isSameAs(authentication);
        MockHttpSession session = (MockHttpSession) request.getSession(false);

        service.logout(request, response);

        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void changesPasswordOnlyAfterVerifyingTheCurrentPassword() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        repository.insertFirst(user("admin.user", encoder.encode(PASSWORD)));
        AdminAuthenticationService service = service(
                repository, encoder, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC),
                (authentication, request, response) -> {});

        assertThatThrownBy(() -> service.changePassword(
                        "admin.user", "incorrect-password", "new-correct-password"))
                .isInstanceOf(InvalidCurrentPasswordException.class);
        service.changePassword("admin.user", PASSWORD, "new-correct-password");

        AdminUser updated = repository.user().orElseThrow();
        assertThat(encoder.matches("new-correct-password", updated.passwordHash())).isTrue();
        assertThat(updated.updatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void fifthConsecutiveFailureBlocksThePairForFifteenMinutes() {
        InMemoryAdminUserRepository repository = new InMemoryAdminUserRepository();
        MutableClock clock = new MutableClock(NOW);
        AdminAuthenticationService service = service(
                repository,
                new BCryptPasswordEncoder(4),
                clock,
                (authentication, request, response) -> {});
        MockHttpServletRequest request = request("203.0.113.5");

        for (int attempt = 1; attempt < 5; attempt++) {
            assertThatThrownBy(() -> service.login(
                            "missing.admin", PASSWORD, request, new MockHttpServletResponse()))
                    .isInstanceOf(InvalidAdminCredentialsException.class);
        }

        assertThatThrownBy(() -> service.login(
                        "missing.admin", PASSWORD, request, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(LoginThrottledException.class, exception ->
                        assertThat(exception.retryAfterSeconds()).isEqualTo(900L));
        assertThatThrownBy(() -> service.login(
                        "missing.admin", PASSWORD, request, new MockHttpServletResponse()))
                .isInstanceOf(LoginThrottledException.class);

        clock.advance(Duration.ofMinutes(15));
        assertThatThrownBy(() -> service.login(
                        "missing.admin", PASSWORD, request, new MockHttpServletResponse()))
                .isInstanceOf(InvalidAdminCredentialsException.class);
    }

    private static Stream<Arguments> invalidSetupPasswords() {
        return Stream.of(
                Arguments.of("admin.user", "too-short"),
                Arguments.of("administrator", "ADMINISTRATOR"),
                Arguments.of("admin.user", " ".repeat(12)),
                Arguments.of("admin.user", "a".repeat(73)),
                Arguments.of("admin.user", "汉".repeat(25)));
    }

    private static AdminAuthenticationService service(
            AdminUserRepository repository,
            PasswordEncoder encoder,
            Clock clock,
            SessionAuthenticationStrategy sessionStrategy) {
        return service(repository, encoder, clock, sessionStrategy, () -> {});
    }

    private static AdminAuthenticationService service(
            AdminUserRepository repository,
            PasswordEncoder encoder,
            Clock clock,
            SessionAuthenticationStrategy sessionStrategy,
            OnboardingSetupListener onboardingSetupListener) {
        return new AdminAuthenticationService(
                repository,
                encoder,
                new HttpSessionSecurityContextRepository(),
                sessionStrategy,
                new AdminLoginAttemptGuard(clock),
                onboardingSetupListener,
                clock);
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static Authentication assertAdminSession(
            MockHttpServletRequest request, String expectedUsername) {
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        assertThat(session).isNotNull();
        SecurityContext context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(context).isNotNull();
        Authentication authentication = context.getAuthentication();
        assertThat(authentication.getName()).isEqualTo(expectedUsername);
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
        return authentication;
    }

    private static AdminUser user(String username, String passwordHash) {
        return new AdminUser(
                java.util.UUID.randomUUID(), username, passwordHash, true, NOW, NOW);
    }

    private static Object outcome(Future<String> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            return exception.getCause();
        }
    }

    private static class InMemoryAdminUserRepository implements AdminUserRepository {
        private final AtomicReference<AdminUser> user = new AtomicReference<>();
        private final AtomicReference<String> lastLookup = new AtomicReference<>();

        @Override
        public boolean exists() {
            return user.get() != null;
        }

        @Override
        public Optional<AdminUser> findByUsername(String username) {
            lastLookup.set(username);
            AdminUser current = user.get();
            return current != null && current.username().equals(username)
                    ? Optional.of(current)
                    : Optional.empty();
        }

        @Override
        public boolean insertFirst(AdminUser candidate) {
            return user.compareAndSet(null, candidate);
        }

        @Override
        public boolean updatePassword(java.util.UUID id, String passwordHash, Instant updatedAt) {
            AdminUser current = user.get();
            if (current == null || !current.id().equals(id)) return false;
            return user.compareAndSet(current, new AdminUser(current.id(), current.username(),
                    passwordHash, current.enabled(), current.createdAt(), updatedAt));
        }

        Optional<AdminUser> user() {
            return Optional.ofNullable(user.get());
        }

        String lastLookup() {
            return lastLookup.get();
        }
    }

    private static final class RacingAdminUserRepository extends InMemoryAdminUserRepository {
        private final CyclicBarrier initialExistsBarrier = new CyclicBarrier(2);
        private final AtomicInteger existsCalls = new AtomicInteger();
        private final AtomicInteger insertedCount = new AtomicInteger();

        @Override
        public boolean exists() {
            if (existsCalls.incrementAndGet() <= 2) {
                try {
                    initialExistsBarrier.await();
                } catch (Exception exception) {
                    throw new IllegalStateException("setup race was interrupted", exception);
                }
            }
            return super.exists();
        }

        @Override
        public boolean insertFirst(AdminUser candidate) {
            boolean inserted = super.insertFirst(candidate);
            if (inserted) {
                insertedCount.incrementAndGet();
            }
            return inserted;
        }

        int insertedCount() {
            return insertedCount.get();
        }
    }

    private static final class DeterministicPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return "{test}" + Integer.toHexString(rawPassword.toString().hashCode());
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encode(rawPassword).equals(encodedPassword);
        }
    }

    private static final class RecordingBcryptPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate = new BCryptPasswordEncoder(4);
        private final List<String> matchedHashes = new ArrayList<>();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchedHashes.add(encodedPassword);
            return delegate.matches(rawPassword, encodedPassword);
        }

        List<String> matchedHashes() {
            return List.copyOf(matchedHashes);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
