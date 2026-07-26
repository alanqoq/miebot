package com.mieai.qqbot.admin.events

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Administrator read API for pending, retrying and completed Outbox jobs. */
@RestController
@RequestMapping("/api/events/outbox", "/api/outbox")
class OutboxController(
    private val service: OutboxAdministrationService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") limit: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) botId: String?,
        @RequestParam(required = false) environment: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) jobType: String?,
    ): ResponseEntity<OutboxPageResponse> = noStore(
        service.list(limit, cursor, query, botId, environment, status, jobType),
    )

    @GetMapping("/stats")
    fun stats(): ResponseEntity<OutboxQueueStatsResponse> = noStore(service.statistics())

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): ResponseEntity<OutboxJobDetailResponse> = noStore(service.get(id))

    private fun <T> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(body)
}
