package com.mieai.qqbot.admin.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AdminAuthController(
    private val authenticationService: AdminAuthenticationService,
) {
    @GetMapping("/status")
    fun status(authentication: Authentication?, csrfToken: CsrfToken): AuthStatusResponse {
        csrfToken.token
        return statusResponse(authentication)
    }

    @PostMapping("/setup")
    fun setup(
        @Valid @RequestBody setup: SetupAdminRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<AuthStatusResponse> {
        val username = authenticationService.setup(
            requireNotNull(setup.username),
            requireNotNull(setup.password),
            request,
            response,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthStatusResponse(false, true, username))
    }

    @PostMapping("/login")
    fun login(
        @Valid @RequestBody login: LoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): AuthStatusResponse {
        val username = authenticationService.login(
            requireNotNull(login.username),
            requireNotNull(login.password),
            request,
            response,
        )
        return AuthStatusResponse(false, true, username)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): AuthStatusResponse {
        authenticationService.logout(request, response)
        return AuthStatusResponse(authenticationService.setupRequired(), false, null)
    }

    @PostMapping("/password")
    fun changePassword(
        @Valid @RequestBody request: ChangePasswordRequest,
        authentication: Authentication?,
    ): AuthStatusResponse {
        if (authentication == null || !authentication.isAuthenticated) {
            throw InvalidCurrentPasswordException()
        }
        authenticationService.changePassword(
            authentication.name,
            requireNotNull(request.currentPassword),
            requireNotNull(request.newPassword),
        )
        return statusResponse(authentication)
    }

    private fun statusResponse(authentication: Authentication?): AuthStatusResponse {
        val authenticated = authentication != null &&
            authentication.isAuthenticated &&
            authentication !is AnonymousAuthenticationToken
        return AuthStatusResponse(
            authenticationService.setupRequired(),
            authenticated,
            if (authenticated) requireNotNull(authentication).name else null,
        )
    }
}
