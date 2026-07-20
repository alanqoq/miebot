package com.mieai.qqbot.admin.plugins;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plugin-bindings")
public class PluginBindingController {
    private final PluginBindingAdministrationService service;

    public PluginBindingController(PluginBindingAdministrationService service) { this.service = service; }

    @GetMapping
    public List<PluginBindingResponse> list(@RequestParam(required = false) String pluginId,
            @RequestParam(required = false) String botId) {
        return service.list(pluginId, botId);
    }

    @PostMapping
    public ResponseEntity<PluginBindingResponse> create(@Valid @RequestBody CreatePluginBindingRequest request) {
        PluginBindingResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/plugin-bindings/" + response.id())).body(response);
    }

    @PutMapping("/{bindingId}")
    public PluginBindingResponse update(@PathVariable UUID bindingId,
            @Valid @RequestBody UpdatePluginBindingRequest request) {
        return service.update(bindingId, request);
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<Void> delete(@PathVariable UUID bindingId) {
        service.delete(bindingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{bindingId}/reset")
    public PluginBindingResponse reset(@PathVariable UUID bindingId) {
        return service.reset(bindingId);
    }
}
