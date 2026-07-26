package com.mieai.qqbot.admin.events

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events/plugin-dlq", "/api/plugin-dead-letters")
class PluginDeadLetterController(
    private val service: PluginDeliveryAdministrationService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") limit: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) bindingId: String?,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<PluginDeliveryPageResponse> = PluginDeliveryController.noStore(
        service.list(limit, cursor, query, bindingId, status, true),
    )

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<PluginDeliveryDetailResponse> =
        PluginDeliveryController.noStore(service.get(id, true))
}
