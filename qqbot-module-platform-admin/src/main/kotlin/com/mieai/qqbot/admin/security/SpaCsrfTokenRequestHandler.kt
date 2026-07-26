package com.mieai.qqbot.admin.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.function.Supplier
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.csrf.CsrfTokenRequestHandler
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler
import org.springframework.util.StringUtils

/** Supports BREACH-protected forms and Angular's plain X-XSRF-TOKEN request header. */
internal class SpaCsrfTokenRequestHandler : CsrfTokenRequestHandler {
    private val plain: CsrfTokenRequestHandler = CsrfTokenRequestAttributeHandler()
    private val xor: CsrfTokenRequestHandler = XorCsrfTokenRequestAttributeHandler()

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        deferredCsrfToken: Supplier<CsrfToken>,
    ) {
        xor.handle(request, response, deferredCsrfToken)
        deferredCsrfToken.get()
    }

    override fun resolveCsrfTokenValue(request: HttpServletRequest, csrfToken: CsrfToken): String? =
        if (StringUtils.hasText(request.getHeader(csrfToken.headerName))) {
            plain.resolveCsrfTokenValue(request, csrfToken)
        } else {
            xor.resolveCsrfTokenValue(request, csrfToken)
        }
}
