package com.mieai.qqbot.admin.security;

import com.mieai.qqbot.admin.onboarding.OnboardingSetupListener;
import com.mieai.qqbot.persistence.admin.AdminUser;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthenticationService {
    private static final int MIN_PASSWORD_CHARACTERS = 12;
    private static final int MAX_BCRYPT_BYTES = 72;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9._-]{3,64}");

    private final AdminUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final AdminLoginAttemptGuard loginAttemptGuard;
    private final OnboardingSetupListener onboardingSetupListener;
    private final Clock clock;
    private final String dummyPasswordHash;

    @Autowired
    public AdminAuthenticationService(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            AdminLoginAttemptGuard loginAttemptGuard,
            OnboardingSetupListener onboardingSetupListener) {
        this(repository,
                passwordEncoder,
                securityContextRepository,
                sessionAuthenticationStrategy,
                loginAttemptGuard,
                onboardingSetupListener,
                Clock.systemUTC());
    }

    AdminAuthenticationService(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            AdminLoginAttemptGuard loginAttemptGuard,
            Clock clock) {
        this(
                repository,
                passwordEncoder,
                securityContextRepository,
                sessionAuthenticationStrategy,
                loginAttemptGuard,
                () -> {},
                clock);
    }

    AdminAuthenticationService(
            AdminUserRepository repository,
            PasswordEncoder passwordEncoder,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            AdminLoginAttemptGuard loginAttemptGuard,
            OnboardingSetupListener onboardingSetupListener,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder must not be null");
        this.securityContextRepository = Objects.requireNonNull(
                securityContextRepository, "securityContextRepository must not be null");
        this.sessionAuthenticationStrategy = Objects.requireNonNull(
                sessionAuthenticationStrategy, "sessionAuthenticationStrategy must not be null");
        this.loginAttemptGuard = Objects.requireNonNull(loginAttemptGuard, "loginAttemptGuard must not be null");
        this.onboardingSetupListener = Objects.requireNonNull(
                onboardingSetupListener, "onboardingSetupListener must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public boolean setupRequired() {
        return !repository.exists();
    }

    public String setup(
            String requestedUsername,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = normalizeUsername(requestedUsername);
        validatePassword(username, password);
        if (repository.exists()) {
            throw new AdminAlreadyConfiguredException();
        }

        Instant now = clock.instant();
        AdminUser user = new AdminUser(
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(password),
                true,
                now,
                now);
        if (!repository.insertFirst(user)) {
            throw new AdminAlreadyConfiguredException();
        }
        onboardingSetupListener.administratorConfigured();
        establishSession(user, request, response);
        return user.username();
    }

    public String login(
            String requestedUsername,
            String password,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = normalizeUsername(requestedUsername);
        validateLoginPassword(password);
        String remoteAddress = request.getRemoteAddr();
        loginAttemptGuard.check(remoteAddress, username);

        AdminUser user = repository.findByUsername(username).orElse(null);
        boolean matches;
        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            matches = false;
        } else {
            matches = user.enabled() && passwordEncoder.matches(password, user.passwordHash());
        }
        if (!matches) {
            loginAttemptGuard.failed(remoteAddress, username);
            throw new InvalidAdminCredentialsException();
        }

        loginAttemptGuard.succeeded(remoteAddress, username);
        establishSession(user, request, response);
        return user.username();
    }

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
    }

    public void changePassword(
            String username,
            String currentPassword,
            String newPassword) {
        String normalizedUsername = normalizeUsername(username);
        validateLoginPassword(currentPassword);
        validatePassword(normalizedUsername, newPassword);
        AdminUser user = repository.findByUsername(normalizedUsername).orElseThrow(
                InvalidCurrentPasswordException::new);
        if (!user.enabled() || !passwordEncoder.matches(currentPassword, user.passwordHash())) {
            throw new InvalidCurrentPasswordException();
        }
        if (!repository.updatePassword(
                user.id(), passwordEncoder.encode(newPassword), clock.instant())) {
            throw new InvalidCurrentPasswordException();
        }
    }

    private void establishSession(
            AdminUser user,
            HttpServletRequest request,
            HttpServletResponse response) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                user.username(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private static String normalizeUsername(String value) {
        Objects.requireNonNull(value, "username must not be null");
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "username must contain 3 to 64 letters, numbers, dots, underscores, or hyphens");
        }
        return normalized;
    }

    private static void validatePassword(String username, String password) {
        Objects.requireNonNull(password, "password must not be null");
        if (password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARACTERS) {
            throw new IllegalArgumentException("password must contain at least 12 characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new IllegalArgumentException("password must not exceed 72 UTF-8 bytes");
        }
        if (password.isBlank() || password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("password must not be blank or equal to the username");
        }
    }

    private static void validateLoginPassword(String password) {
        Objects.requireNonNull(password, "password must not be null");
        if (password.isEmpty() || password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
            throw new InvalidAdminCredentialsException();
        }
    }
}
