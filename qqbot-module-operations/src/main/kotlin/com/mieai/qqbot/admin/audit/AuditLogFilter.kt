package com.mieai.qqbot.admin.audit

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.beans.factory.ObjectProvider
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Records mutation metadata only; it never reads or persists request bodies. */
@Component
class AuditLogFilter(
    private val serviceProvider: ObjectProvider<AuditLogAdministrationService>,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val mutation = request.method in MUTATIONS &&
            request.requestURI.startsWith("/api/") &&
            request.requestURI != "/api/audit-logs"
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (mutation) {
                val service = serviceProvider.ifAvailable
                if (service != null) {
                    val authentication = SecurityContextHolder.getContext().authentication
                    val actor = if (authentication?.isAuthenticated == true) authentication.name else null
                    try {
                        service.append(
                            actor,
                            request.method,
                            request.requestURI,
                            response.status,
                            request.remoteAddr,
                            MDC.get("traceId"),
                        )
                    } catch (_: RuntimeException) {
                        // Auditing must not turn a committed business response into a 500.
                    }
                }
            }
        }
    }

    private companion object {
        val MUTATIONS = setOf("POST", "PUT", "PATCH", "DELETE")
    }
}
