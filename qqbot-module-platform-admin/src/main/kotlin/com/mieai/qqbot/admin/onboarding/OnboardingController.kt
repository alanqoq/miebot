package com.mieai.qqbot.admin.onboarding

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system/onboarding")
class OnboardingController(
    private val service: OnboardingAdministrationService,
) {
    @GetMapping
    fun status(): OnboardingStatusResponse = service.status()

    @PostMapping("/database-configured")
    fun databaseConfigured(
        @Valid @RequestBody request: DatabaseConfiguredRequest,
    ): OnboardingStatusResponse = service.databaseConfigured(
        request.expectedRevision,
        requireNotNull(request.databaseType),
    )

    @PostMapping("/complete")
    fun complete(): OnboardingStatusResponse = service.complete()
}
