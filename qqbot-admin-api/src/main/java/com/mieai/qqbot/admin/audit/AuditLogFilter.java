package com.mieai.qqbot.admin.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Records mutation metadata only; it never reads or persists request bodies. */
@Component
public final class AuditLogFilter extends OncePerRequestFilter {
    private static final Set<String> MUTATIONS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final ObjectProvider<AuditLogAdministrationService> serviceProvider;

    public AuditLogFilter(ObjectProvider<AuditLogAdministrationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean mutation = MUTATIONS.contains(request.getMethod())
                && request.getRequestURI().startsWith("/api/")
                && !request.getRequestURI().equals("/api/audit-logs");
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (mutation) {
                AuditLogAdministrationService service = serviceProvider.getIfAvailable();
                if (service == null) return;
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                String actor = authentication == null || !authentication.isAuthenticated()
                        ? null : authentication.getName();
                try {
                    service.append(actor, request.getMethod(), request.getRequestURI(),
                            response.getStatus(), request.getRemoteAddr(), MDC.get("traceId"));
                } catch (RuntimeException ignored) {
                    // Auditing must not turn a committed business response into a 500.
                }
            }
        }
    }
}
