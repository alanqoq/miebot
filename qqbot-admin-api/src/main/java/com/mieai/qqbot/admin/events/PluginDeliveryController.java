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
@RequestMapping({"/api/events/plugin-deliveries", "/api/plugin-deliveries"})
public class PluginDeliveryController {
    private final PluginDeliveryAdministrationService service;

    public PluginDeliveryController(PluginDeliveryAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<PluginDeliveryPageResponse> list(
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String bindingId,
            @RequestParam(required = false) String status) {
        return noStore(service.list(limit, cursor, query, bindingId, status, false));
    }

    @GetMapping("/stats")
    public ResponseEntity<PluginDeliveryQueueStatsResponse> stats() {
        return noStore(service.statistics());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PluginDeliveryDetailResponse> get(@PathVariable String id) {
        return noStore(service.get(id, false));
    }

    static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
