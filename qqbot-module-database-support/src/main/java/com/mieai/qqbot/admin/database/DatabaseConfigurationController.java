package com.mieai.qqbot.admin.database;

import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system/database")
public class DatabaseConfigurationController {
    private final DatabaseAdministrationService service;

    public DatabaseConfigurationController(DatabaseAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping("/configuration")
    public ResponseEntity<DatabaseConfigurationView> configuration() {
        return configurationResponse(service.current());
    }

    @PostMapping("/test")
    public DatabaseTestResult test(@Valid @RequestBody DatabaseCandidateRequest request) {
        DatabasePassword password = request.password() == null ? null : DatabasePassword.of(request.password());
        try (password) {
            return service.test(request.toSettings(password));
        }
    }

    @PostMapping("/switch")
    public ResponseEntity<DatabaseSwitchResult> switchDatabase(
            @Valid @RequestBody DatabaseSwitchRequest request,
            Authentication authentication) {
        DatabaseCandidateRequest candidate = request.candidate();
        DatabasePassword password = candidate.password() == null ? null : DatabasePassword.of(candidate.password());
        DatabaseSwitchResult result;
        try (password) {
            result = service.switchDatabase(
                    request.expectedRevision(),
                    candidate.toSettings(password),
                    authentication.getName());
        }
        return switchResponse(result);
    }

    @PostMapping("/reload")
    public ResponseEntity<DatabaseSwitchResult> reload(
            @Valid @RequestBody ReloadDatabaseRequest request,
            Authentication authentication) {
        return switchResponse(service.reload(request.expectedRevision(), authentication.getName()));
    }

    private static ResponseEntity<DatabaseConfigurationView> configurationResponse(
            DatabaseConfigurationView configuration) {
        return ResponseEntity.ok()
                .eTag(etag(configuration.revision()))
                .body(configuration);
    }

    private static ResponseEntity<DatabaseSwitchResult> switchResponse(DatabaseSwitchResult result) {
        return ResponseEntity.ok()
                .eTag(etag(result.configuration().revision()))
                .body(result);
    }

    private static String etag(long revision) {
        return '"' + Long.toString(revision) + '"';
    }
}
