package com.mieai.qqbot.admin.onboarding;

import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/onboarding")
public class OnboardingController {
    private final OnboardingAdministrationService service;

    public OnboardingController(OnboardingAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public OnboardingStatusResponse status() {
        return service.status();
    }

    @PostMapping("/database-configured")
    public OnboardingStatusResponse databaseConfigured(
            @Valid @RequestBody DatabaseConfiguredRequest request) {
        return service.databaseConfigured(request.expectedRevision(), request.databaseType());
    }

    @PostMapping("/complete")
    public OnboardingStatusResponse complete() {
        return service.complete();
    }
}
