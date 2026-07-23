package com.mieai.qqbot.admin.events;

import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/events/plugin-dlq", "/api/plugin-dead-letters"})
public class PluginDeadLetterController {
    private final PluginDeliveryAdministrationService service;

    public PluginDeadLetterController(PluginDeliveryAdministrationService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @GetMapping
    public ResponseEntity<PluginDeliveryPageResponse> list(
            @RequestParam(defaultValue = "50") String limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String bindingId,
            @RequestParam(required = false) String status) {
        return PluginDeliveryController.noStore(
                service.list(limit, cursor, query, bindingId, status, true));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PluginDeliveryDetailResponse> get(@PathVariable String id) {
        return PluginDeliveryController.noStore(service.get(id, true));
    }
}
