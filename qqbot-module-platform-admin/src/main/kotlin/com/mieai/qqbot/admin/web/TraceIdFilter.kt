package com.mieai.qqbot.admin.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.util.UUID
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TraceIdFilter : OncePerRequestFilter() {
    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requested = request.getHeader(HEADER_NAME)
        val traceId = requested?.takeIf { SAFE_TRACE_ID.matches(it) } ?: UUID.randomUUID().toString()
        response.setHeader(HEADER_NAME, traceId)
        MDC.put("traceId", traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("traceId")
        }
    }

    companion object {
        const val HEADER_NAME = "X-Trace-Id"
        private val SAFE_TRACE_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
