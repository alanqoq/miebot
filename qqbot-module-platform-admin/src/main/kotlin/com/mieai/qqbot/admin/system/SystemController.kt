package com.mieai.qqbot.admin.system

import java.time.Clock
import javax.sql.DataSource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system")
class SystemController internal constructor(
    private val dataSource: DataSource,
    private val buildProperties: ObjectProvider<BuildProperties>,
    private val clock: Clock,
) {

    @Autowired
    constructor(
        dataSource: DataSource,
        buildProperties: ObjectProvider<BuildProperties>,
    ) : this(dataSource, buildProperties, Clock.systemUTC())

    @GetMapping("/info")
    fun info(): SystemInfoResponse {
        val build = buildProperties.getIfAvailable()
        return SystemInfoResponse(
            "qqbot-platform",
            build?.version ?: "development",
            "RUNNING",
            databaseProduct(),
            clock.instant(),
        )
    }

    private fun databaseProduct(): String = try {
        dataSource.connection.use { it.metaData.databaseProductName }
    } catch (_: Exception) {
        "Unavailable"
    }
}
