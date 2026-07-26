package com.mieai.qqbot.admin.events

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/events/plugin-deliveries", "/api/plugin-deliveries")
class PluginDeliveryController(
    private val service: PluginDeliveryAdministrationService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") limit: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) bindingId: String?,
        @RequestParam(required = false) status: String?,
    ): ResponseEntity<PluginDeliveryPageResponse> =
        noStore(service.list(limit, cursor, query, bindingId, status, false))

    @GetMapping("/stats")
    fun stats(): ResponseEntity<PluginDeliveryQueueStatsResponse> = noStore(service.statistics())

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<PluginDeliveryDetailResponse> =
        noStore(service.get(id, false))

    companion object {
        fun <T> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(body)
    }
}
