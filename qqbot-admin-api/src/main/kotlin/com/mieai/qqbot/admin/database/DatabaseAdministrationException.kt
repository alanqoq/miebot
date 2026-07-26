package com.mieai.qqbot.admin.database

import org.springframework.http.HttpStatus

class DatabaseAdministrationException(
    status: HttpStatus,
    code: String,
    message: String?,
    fieldErrors: Map<String, String> = emptyMap(),
) : RuntimeException(message) {
    val status = status
    val code = requireText(code, "code")
    val fieldErrors = fieldErrors.toMap()
    private companion object {
        fun requireText(value: String, name: String): String {
            require(value.isNotBlank()) { "$name must not be blank" }
            return value
        }
    }
}
