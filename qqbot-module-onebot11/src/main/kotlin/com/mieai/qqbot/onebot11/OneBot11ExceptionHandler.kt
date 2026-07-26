package com.mieai.qqbot.onebot11

import com.mieai.qqbot.onebot11.config.OneBot11RevisionConflictException
import com.mieai.qqbot.runtime.configuration.BotNotFoundException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [OneBot11SettingsController::class])
class OneBot11ExceptionHandler {
    @ExceptionHandler(OneBot11RevisionConflictException::class)
    fun revisionConflict(): ResponseEntity<Map<String, String>> = ResponseEntity
        .status(HttpStatus.CONFLICT)
        .body(
            mapOf(
                "code" to "REVISION_CONFLICT",
                "message" to "OneBot configuration revision changed",
            ),
        )

    @ExceptionHandler(BotNotFoundException::class)
    fun botNotFound(): ResponseEntity<Map<String, String>> = ResponseEntity
        .status(HttpStatus.NOT_FOUND)
        .body(mapOf("code" to "BOT_NOT_FOUND", "message" to "Bot was not found"))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(exception: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(
            mapOf(
                "code" to "INVALID_ONEBOT_SETTINGS",
                "message" to (exception.message ?: "OneBot settings are invalid"),
            ),
        )
}
