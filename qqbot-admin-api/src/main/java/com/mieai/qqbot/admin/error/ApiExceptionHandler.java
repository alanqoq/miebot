package com.mieai.qqbot.admin.error;

import com.mieai.qqbot.persistence.bot.OptimisticLockException;
import com.mieai.qqbot.admin.database.DatabaseAdministrationException;
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException;
import com.mieai.qqbot.admin.plugins.PluginAdministrationException;
import com.mieai.qqbot.admin.security.AdminAlreadyConfiguredException;
import com.mieai.qqbot.admin.security.InvalidAdminCredentialsException;
import com.mieai.qqbot.admin.security.InvalidCurrentPasswordException;
import com.mieai.qqbot.admin.security.LoginThrottledException;
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.security.KeyUnavailableException;
import com.mieai.qqbot.admin.bot.MediaUploadTooLargeException;
import com.mieai.qqbot.admin.bot.BotMessageAdministrationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", fields);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody() {
        return response(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Malformed JSON request", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> uploadTooLarge(HttpServletRequest request) {
        if (request.getRequestURI().matches("/api/bots/[^/]+/media")) {
            return response(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_FILE_TOO_LARGE",
                    "媒体上传请求不能超过平台上限 256 MiB，机器人配置的上限可能更低", Map.of());
        }
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "PLUGIN_FILE_TOO_LARGE",
                "插件 JAR 不能超过 64 MiB", Map.of());
    }

    @ExceptionHandler(MediaUploadTooLargeException.class)
    ResponseEntity<ApiErrorResponse> mediaUploadTooLarge(MediaUploadTooLargeException exception) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_FILE_TOO_LARGE",
                "媒体文件超过该机器人配置的上传上限（" + exception.maxBytes() + " bytes）", Map.of());
    }

    @ExceptionHandler(BotMessageAdministrationException.class)
    ResponseEntity<ApiErrorResponse> botMessage(BotMessageAdministrationException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler({NoSuchElementException.class, BotNotFoundException.class})
    ResponseEntity<ApiErrorResponse> notFound(RuntimeException exception) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(OptimisticLockException.class)
    ResponseEntity<ApiErrorResponse> conflict(OptimisticLockException exception) {
        return response(HttpStatus.CONFLICT, "REVISION_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AdminAlreadyConfiguredException.class)
    ResponseEntity<ApiErrorResponse> adminAlreadyConfigured(AdminAlreadyConfiguredException exception) {
        return response(
                HttpStatus.CONFLICT,
                "ADMIN_ALREADY_CONFIGURED",
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler({InvalidAdminCredentialsException.class, InvalidCurrentPasswordException.class})
    ResponseEntity<ApiErrorResponse> invalidCredentials() {
        return response(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "The username or password is invalid",
                Map.of());
    }

    @ExceptionHandler(LoginThrottledException.class)
    ResponseEntity<ApiErrorResponse> loginThrottled(LoginThrottledException exception) {
        ResponseEntity<ApiErrorResponse> base = response(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGIN_THROTTLED",
                "Too many failed sign-in attempts",
                Map.of());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(base.getBody());
    }

    @ExceptionHandler(KeyUnavailableException.class)
    ResponseEntity<ApiErrorResponse> keyUnavailable() {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "APP_SECRET_KEY_UNAVAILABLE",
                "AppSecret master key is not configured",
                Map.of());
    }

    @ExceptionHandler(DatabaseAdministrationException.class)
    ResponseEntity<ApiErrorResponse> databaseAdministration(DatabaseAdministrationException exception) {
        return response(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                exception.fieldErrors());
    }

    @ExceptionHandler(OnboardingOperationException.class)
    ResponseEntity<ApiErrorResponse> onboardingOperation(OnboardingOperationException exception) {
        return response(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                Map.of());
    }

    @ExceptionHandler(PluginAdministrationException.class)
    ResponseEntity<ApiErrorResponse> pluginAdministration(PluginAdministrationException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> internal(Exception exception) {
        LOGGER.error("Unhandled administration API failure", exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed",
                Map.of());
    }

    private static ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fields) {
        String traceId = MDC.get("traceId");
        ApiErrorResponse error = new ApiErrorResponse(
                code,
                message == null || message.isBlank() ? status.getReasonPhrase() : message,
                Map.copyOf(fields),
                traceId == null ? "unavailable" : traceId,
                Instant.now());
        return ResponseEntity.status(status).body(error);
    }
}
