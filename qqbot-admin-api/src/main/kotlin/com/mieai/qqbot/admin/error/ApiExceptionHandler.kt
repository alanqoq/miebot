package com.mieai.qqbot.admin.error

import com.mieai.qqbot.admin.bot.BotMessageAdministrationException
import com.mieai.qqbot.admin.bot.MediaUploadTooLargeException
import com.mieai.qqbot.admin.database.DatabaseAdministrationException
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException
import com.mieai.qqbot.admin.plugins.PluginAdministrationException
import com.mieai.qqbot.admin.security.AdminAlreadyConfiguredException
import com.mieai.qqbot.admin.security.InvalidAdminCredentialsException
import com.mieai.qqbot.admin.security.InvalidCurrentPasswordException
import com.mieai.qqbot.admin.security.LoginThrottledException
import com.mieai.qqbot.persistence.bot.OptimisticLockException
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import com.mieai.qqbot.runtime.security.KeyUnavailableException
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.NoSuchElementException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(exception: MethodArgumentNotValidException): ResponseEntity<ApiErrorResponse> {
        val fields = linkedMapOf<String, String>()
        for (error: FieldError in exception.bindingResult.fieldErrors) {
            fields.putIfAbsent(error.field, error.defaultMessage ?: "Invalid value")
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(exception: IllegalArgumentException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.message, emptyMap())

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(exception: MethodArgumentTypeMismatchException): ResponseEntity<ApiErrorResponse> =
        response(
            HttpStatus.BAD_REQUEST,
            "INVALID_REQUEST",
            "Invalid value for parameter '${exception.name}'",
            emptyMap(),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Malformed JSON request", emptyMap())

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun uploadTooLarge(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        if (request.requestURI.matches(Regex("/api/bots/[^/]+/media"))) {
            response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "MEDIA_FILE_TOO_LARGE",
                "媒体上传请求不能超过平台上限 256 MiB，机器人配置的上限可能更低",
                emptyMap(),
            )
        } else {
            response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "UPLOAD_TOO_LARGE",
                "上传请求不能超过平台上限 256 MiB",
                emptyMap(),
            )
        }

    @ExceptionHandler(MediaUploadTooLargeException::class)
    fun mediaUploadTooLarge(exception: MediaUploadTooLargeException): ResponseEntity<ApiErrorResponse> = response(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "MEDIA_FILE_TOO_LARGE",
        "媒体文件超过该机器人配置的上传上限（${exception.maxBytes} bytes）",
        emptyMap(),
    )

    @ExceptionHandler(BotMessageAdministrationException::class)
    fun botMessage(exception: BotMessageAdministrationException): ResponseEntity<ApiErrorResponse> =
        response(exception.status, exception.code, exception.message, emptyMap())

    @ExceptionHandler(NoSuchElementException::class, BotNotFoundException::class)
    fun notFound(exception: RuntimeException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.message, emptyMap())

    @ExceptionHandler(OptimisticLockException::class)
    fun conflict(exception: OptimisticLockException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.message, emptyMap())

    @ExceptionHandler(AdminAlreadyConfiguredException::class)
    fun adminAlreadyConfigured(exception: AdminAlreadyConfiguredException): ResponseEntity<ApiErrorResponse> =
        response(HttpStatus.CONFLICT, "ADMIN_ALREADY_CONFIGURED", exception.message, emptyMap())

    @ExceptionHandler(InvalidAdminCredentialsException::class, InvalidCurrentPasswordException::class)
    fun invalidCredentials(): ResponseEntity<ApiErrorResponse> = response(
        HttpStatus.UNAUTHORIZED,
        "INVALID_CREDENTIALS",
        "The username or password is invalid",
        emptyMap(),
    )

    @ExceptionHandler(LoginThrottledException::class)
    fun loginThrottled(exception: LoginThrottledException): ResponseEntity<ApiErrorResponse> {
        val base = response(
            HttpStatus.TOO_MANY_REQUESTS,
            "LOGIN_THROTTLED",
            "Too many failed sign-in attempts",
            emptyMap(),
        )
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, exception.retryAfterSeconds.toString())
            .body(base.body)
    }

    @ExceptionHandler(KeyUnavailableException::class)
    fun keyUnavailable(): ResponseEntity<ApiErrorResponse> = response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "APP_SECRET_KEY_UNAVAILABLE",
        "AppSecret master key is not configured",
        emptyMap(),
    )

    @ExceptionHandler(DatabaseAdministrationException::class)
    fun databaseAdministration(exception: DatabaseAdministrationException): ResponseEntity<ApiErrorResponse> =
        response(exception.status, exception.code, exception.message, exception.fieldErrors)

    @ExceptionHandler(OnboardingOperationException::class)
    fun onboardingOperation(exception: OnboardingOperationException): ResponseEntity<ApiErrorResponse> =
        response(exception.status, exception.code, exception.message, emptyMap())

    @ExceptionHandler(PluginAdministrationException::class)
    fun pluginAdministration(exception: PluginAdministrationException): ResponseEntity<ApiErrorResponse> =
        response(exception.status, exception.code, exception.message, emptyMap())

    @ExceptionHandler(Exception::class)
    fun internal(exception: Exception): ResponseEntity<ApiErrorResponse> {
        LOGGER.error("Unhandled administration API failure", exception)
        return response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "The request could not be completed",
            emptyMap(),
        )
    }

    private fun response(
        status: HttpStatus,
        code: String,
        message: String?,
        fields: Map<String, String>,
    ): ResponseEntity<ApiErrorResponse> {
        val traceId = MDC.get("traceId")
        val error = ApiErrorResponse(
            code,
            if (message.isNullOrBlank()) status.reasonPhrase else message,
            fields.toMap(),
            traceId ?: "unavailable",
            Instant.now(),
        )
        return ResponseEntity.status(status).body(error)
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
