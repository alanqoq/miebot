package com.mieai.qqbot.admin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AdminAuthController {
    private final AdminAuthenticationService authenticationService;

    public AdminAuthController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/status")
    public AuthStatusResponse status(Authentication authentication, CsrfToken csrfToken) {
        csrfToken.getToken();
        return statusResponse(authentication);
    }

    @PostMapping("/setup")
    public ResponseEntity<AuthStatusResponse> setup(
            @Valid @RequestBody SetupAdminRequest setup,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = authenticationService.setup(setup.username(), setup.password(), request, response);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthStatusResponse(false, true, username));
    }

    @PostMapping("/login")
    public AuthStatusResponse login(
            @Valid @RequestBody LoginRequest login,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = authenticationService.login(login.username(), login.password(), request, response);
        return new AuthStatusResponse(false, true, username);
    }

    @PostMapping("/logout")
    public AuthStatusResponse logout(HttpServletRequest request, HttpServletResponse response) {
        authenticationService.logout(request, response);
        return new AuthStatusResponse(authenticationService.setupRequired(), false, null);
    }

    @PostMapping("/password")
    public AuthStatusResponse changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new InvalidCurrentPasswordException();
        }
        authenticationService.changePassword(
                authentication.getName(), request.currentPassword(), request.newPassword());
        return statusResponse(authentication);
    }

    private AuthStatusResponse statusResponse(Authentication authentication) {
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return new AuthStatusResponse(
                authenticationService.setupRequired(),
                authenticated,
                authenticated ? authentication.getName() : null);
    }
}
