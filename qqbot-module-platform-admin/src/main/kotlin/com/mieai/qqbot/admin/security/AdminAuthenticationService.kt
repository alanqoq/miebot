package com.mieai.qqbot.admin.security

import com.mieai.qqbot.admin.onboarding.OnboardingSetupListener
import com.mieai.qqbot.persistence.admin.AdminUser
import com.mieai.qqbot.persistence.admin.AdminUserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Locale
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Service

@Service
class AdminAuthenticationService internal constructor(
    private val repository: AdminUserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val securityContextRepository: SecurityContextRepository,
    private val sessionAuthenticationStrategy: SessionAuthenticationStrategy,
    private val loginAttemptGuard: AdminLoginAttemptGuard,
    private val onboardingSetupListener: OnboardingSetupListener,
    private val clock: Clock,
) {
    private val dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString())

    @Autowired
    constructor(
        repository: AdminUserRepository,
        passwordEncoder: PasswordEncoder,
        securityContextRepository: SecurityContextRepository,
        sessionAuthenticationStrategy: SessionAuthenticationStrategy,
        loginAttemptGuard: AdminLoginAttemptGuard,
        onboardingSetupListener: OnboardingSetupListener,
    ) : this(
        repository,
        passwordEncoder,
        securityContextRepository,
        sessionAuthenticationStrategy,
        loginAttemptGuard,
        onboardingSetupListener,
        Clock.systemUTC(),
    )

    fun setupRequired(): Boolean = !repository.exists()

    fun setup(
        requestedUsername: String,
        password: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val username = normalizeUsername(requestedUsername)
        validatePassword(username, password)
        if (repository.exists()) throw AdminAlreadyConfiguredException()

        val now = clock.instant()
        val user = AdminUser(
            UUID.randomUUID(),
            username,
            passwordEncoder.encode(password),
            true,
            now,
            now,
        )
        if (!repository.insertFirst(user)) throw AdminAlreadyConfiguredException()
        onboardingSetupListener.administratorConfigured()
        establishSession(user, request, response)
        return user.username
    }

    fun login(
        requestedUsername: String,
        password: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): String {
        val username = normalizeUsername(requestedUsername)
        validateLoginPassword(password)
        val remoteAddress = request.remoteAddr
        loginAttemptGuard.check(remoteAddress, username)

        val user = repository.findByUsername(username)
        val authenticatedUser = if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash)
            null
        } else {
            user.takeIf {
                it.enabled && passwordEncoder.matches(password, it.passwordHash)
            }
        }
        if (authenticatedUser == null) {
            loginAttemptGuard.failed(remoteAddress, username)
            throw InvalidAdminCredentialsException()
        }

        loginAttemptGuard.succeeded(remoteAddress, username)
        establishSession(authenticatedUser, request, response)
        return authenticatedUser.username
    }

    fun logout(request: HttpServletRequest, response: HttpServletResponse) {
        val authentication = SecurityContextHolder.getContext().authentication
        SecurityContextLogoutHandler().logout(request, response, authentication)
    }

    fun changePassword(username: String, currentPassword: String, newPassword: String) {
        val normalizedUsername = normalizeUsername(username)
        validateLoginPassword(currentPassword)
        validatePassword(normalizedUsername, newPassword)
        val user = repository.findByUsername(normalizedUsername)
            ?: throw InvalidCurrentPasswordException()
        if (!user.enabled || !passwordEncoder.matches(currentPassword, user.passwordHash)) {
            throw InvalidCurrentPasswordException()
        }
        if (!repository.updatePassword(user.id, passwordEncoder.encode(newPassword), clock.instant())) {
            throw InvalidCurrentPasswordException()
        }
    }

    private fun establishSession(
        user: AdminUser,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val authentication: Authentication = UsernamePasswordAuthenticationToken.authenticated(
            user.username,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response)
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = authentication
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
    }

    private companion object {
        const val MIN_PASSWORD_CHARACTERS = 12
        const val MAX_BCRYPT_BYTES = 72
        val USERNAME = Regex("[a-z0-9._-]{3,64}")

        fun normalizeUsername(value: String): String {
            val normalized = value.trim().lowercase(Locale.ROOT)
            require(USERNAME.matches(normalized)) {
                "username must contain 3 to 64 letters, numbers, dots, underscores, or hyphens"
            }
            return normalized
        }

        fun validatePassword(username: String, password: String) {
            require(password.codePointCount(0, password.length) >= MIN_PASSWORD_CHARACTERS) {
                "password must contain at least 12 characters"
            }
            require(password.toByteArray(StandardCharsets.UTF_8).size <= MAX_BCRYPT_BYTES) {
                "password must not exceed 72 UTF-8 bytes"
            }
            require(!password.isBlank() && !password.equals(username, ignoreCase = true)) {
                "password must not be blank or equal to the username"
            }
        }

        fun validateLoginPassword(password: String) {
            if (password.isEmpty() || password.toByteArray(StandardCharsets.UTF_8).size > MAX_BCRYPT_BYTES) {
                throw InvalidAdminCredentialsException()
            }
        }
    }
}
