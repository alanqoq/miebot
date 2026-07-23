package com.mieai.qqbot.admin.events;

import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/inbox")
public class InboxController {
    private final InboxAdministrationService service;

    public InboxController(InboxAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<InboxPageResponse> list(
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String botId,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType) {
        return noStore(service.list(limit, cursor, query, botId, environment, status, eventType));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InboxEventDetailResponse> get(@PathVariable String id) {
        return noStore(service.get(id));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
