package com.mieai.qqbot.admin.events;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator read API for terminal Outbox jobs in the dead-letter queue. */
@RestController
@RequestMapping({"/api/events/dlq", "/api/dead-letters"})
public class DeadLetterController {
    private final OutboxAdministrationService service;

    public DeadLetterController(OutboxAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<OutboxPageResponse> list(
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jobType) {
        return noStore(service.list(limit, cursor, query, botId, environment, status, jobType, true));
    }

    @GetMapping("/stats")
    public ResponseEntity<OutboxQueueStatsResponse> stats() {
        return noStore(service.statistics());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutboxJobDetailResponse> get(@PathVariable String id) {
        return noStore(service.get(id, true));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
