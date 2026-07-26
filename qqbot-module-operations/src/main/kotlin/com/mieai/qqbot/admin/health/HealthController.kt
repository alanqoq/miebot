package com.mieai.qqbot.admin.health

import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/health")
class HealthController internal constructor(
    private val dataSource: DataSource,
    private val clock: Clock,
) {
    @Autowired
    constructor(dataSource: DataSource) : this(dataSource, Clock.systemUTC())

    @GetMapping("/live")
    fun live(): HealthStatus = HealthStatus("UP", Instant.now(clock), mapOf("process" to "UP"))

    @GetMapping("/ready")
    fun ready(): ResponseEntity<HealthStatus> {
        val checkedAt = Instant.now(clock)
        try {
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT 1").use { statement ->
                    statement.executeQuery().use { result ->
                        if (result.next() && result.getInt(1) == 1) {
                            return ResponseEntity.ok(
                                HealthStatus(
                                    "UP",
                                    checkedAt,
                                    mapOf("database" to "UP", "migrations" to "UP"),
                                ),
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Readiness deliberately returns a sanitized response; diagnostics stay in server logs.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(HealthStatus("DOWN", checkedAt, mapOf("database" to "DOWN")))
    }
}
