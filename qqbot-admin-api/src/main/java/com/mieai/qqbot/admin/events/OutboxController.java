package com.mieai.qqbot.admin.events;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Administrator read API for pending, retrying and completed Outbox jobs. */
@RestController
@RequestMapping({"/api/events/outbox", "/api/outbox"})
public class OutboxController {
    private final OutboxAdministrationService service;

    public OutboxController(OutboxAdministrationService service) {
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
        return noStore(service.list(limit, cursor, query, botId, environment, status, jobType));
    }

    @GetMapping("/stats")
    public ResponseEntity<OutboxQueueStatsResponse> stats() {
        return noStore(service.statistics());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OutboxJobDetailResponse> get(@PathVariable String id) {
        return noStore(service.get(id));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
