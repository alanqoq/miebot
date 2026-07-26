package com.mieai.qqbot.admin.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.admin.error.ApiErrorResponse
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy
import org.springframework.security.web.csrf.CsrfTokenRepository

@Configuration(proxyBeanMethods = false)
class AdminSecurityConfiguration {
    @Bean
    @Throws(Exception::class)
    fun adminSecurityFilterChain(
        http: HttpSecurity,
        csrfTokenRepository: CsrfTokenRepository,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        http.authorizeHttpRequests { authorize ->
            authorize
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/favicon.ico",
                    "/*.css",
                    "/*.js",
                    "/assets/**",
                    "/login",
                    "/modules/**",
                    "/setup",
                    "/health/**",
                    "/api/system/info",
                    "/api/auth/status",
                    "/api/auth/setup",
                    "/api/auth/login",
                    "/error",
                )
                .permitAll()
                .requestMatchers("/api/**")
                .hasRole("ADMIN")
                .anyRequest()
                .authenticated()
        }
        http.csrf { csrf ->
            csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(SpaCsrfTokenRequestHandler())
        }
        http.exceptionHandling { exceptions ->
            exceptions
                .authenticationEntryPoint { _, response, _ ->
                    writeSecurityError(
                        response,
                        objectMapper,
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "Administrator authentication is required",
                    )
                }
                .accessDeniedHandler { _, response, _ ->
                    writeSecurityError(
                        response,
                        objectMapper,
                        HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED",
                        "The request is not permitted",
                    )
                }
        }
        http.requestCache { it.disable() }
        http.httpBasic { it.disable() }
        http.formLogin { it.disable() }
        http.logout { it.disable() }
        http.headers { headers ->
            headers.contentSecurityPolicy { csp ->
                csp.policyDirectives(
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; " +
                        "object-src 'none'; frame-ancestors 'none'; base-uri 'self'",
                )
            }
        }
        return http.build()
    }

    @Bean
    fun csrfTokenRepository(
        @Value("\${qqbot.security.cookie-secure:false}") cookieSecure: Boolean,
    ): CsrfTokenRepository {
        val repository = CookieCsrfTokenRepository.withHttpOnlyFalse()
        repository.setCookieCustomizer { cookie ->
            cookie.path("/").sameSite("Lax").secure(cookieSecure)
        }
        return repository
    }

    @Bean
    fun adminSecurityContextRepository(): SecurityContextRepository =
        HttpSessionSecurityContextRepository()

    @Bean
    fun adminSessionAuthenticationStrategy(
        csrfTokenRepository: CsrfTokenRepository,
    ): SessionAuthenticationStrategy {
        val strategies: List<SessionAuthenticationStrategy> = listOf(
            ChangeSessionIdAuthenticationStrategy(),
            CsrfAuthenticationStrategy(csrfTokenRepository),
        )
        return CompositeSessionAuthenticationStrategy(strategies)
    }

    @Bean
    fun adminPasswordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean
    fun noDefaultInMemoryUsers(): UserDetailsService = UserDetailsService {
        throw UsernameNotFoundException("User not found")
    }

    @Throws(IOException::class)
    private fun writeSecurityError(
        response: HttpServletResponse,
        objectMapper: ObjectMapper,
        status: HttpStatus,
        code: String,
        message: String,
    ) {
        val traceId = MDC.get("traceId")
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = StandardCharsets.UTF_8.name()
        objectMapper.writeValue(
            response.outputStream,
            ApiErrorResponse(code, message, emptyMap(), traceId ?: "unavailable", Instant.now()),
        )
    }
}
