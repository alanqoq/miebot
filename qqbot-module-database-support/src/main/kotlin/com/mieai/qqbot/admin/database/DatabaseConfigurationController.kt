package com.mieai.qqbot.admin.database

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system/database")
class DatabaseConfigurationController(
    private val service: DatabaseAdministrationService,
) {
    @GetMapping("/configuration")
    fun configuration(): ResponseEntity<DatabaseConfigurationView> = configurationResponse(service.current())

    @PostMapping("/test")
    fun test(@Valid @RequestBody request: DatabaseCandidateRequest): DatabaseTestResult {
        val password = request.password?.let(DatabasePassword::of)
        try {
            return service.test(request.toSettings(password))
        } finally {
            password?.close()
        }
    }

    @PostMapping("/switch")
    fun switchDatabase(
        @Valid @RequestBody request: DatabaseSwitchRequest,
        authentication: Authentication,
    ): ResponseEntity<DatabaseSwitchResult> {
        val candidate = requireNotNull(request.candidate)
        val password = candidate.password?.let(DatabasePassword::of)
        try {
            val result = service.switchDatabase(
                request.expectedRevision,
                candidate.toSettings(password),
                authentication.name,
            )
            return switchResponse(result)
        } finally {
            password?.close()
        }
    }

    @PostMapping("/reload")
    fun reload(
        @Valid @RequestBody request: ReloadDatabaseRequest,
        authentication: Authentication,
    ): ResponseEntity<DatabaseSwitchResult> =
        switchResponse(service.reload(request.expectedRevision, authentication.name))

    private companion object {
        fun configurationResponse(configuration: DatabaseConfigurationView): ResponseEntity<DatabaseConfigurationView> =
            ResponseEntity.ok().eTag(etag(configuration.revision)).body(configuration)

        fun switchResponse(result: DatabaseSwitchResult): ResponseEntity<DatabaseSwitchResult> =
            ResponseEntity.ok().eTag(etag(result.configuration.revision)).body(result)

        fun etag(revision: Long): String = "\"$revision\""
    }
}
