package com.mieai.qqbot.app.database

import com.mieai.qqbot.admin.database.DatabaseSchemaState
import javax.sql.DataSource

class DatabaseCandidate(
    val dataSource: DataSource,
    val product: String,
    val version: String,
    val latencyMs: Long,
    val schemaState: DatabaseSchemaState,
    val schemaVersion: String?,
    val initialized: Boolean,
    val botCount: Long,
) : AutoCloseable {
    private var transferred = false

    fun transferDataSource(): DataSource {
        check(!transferred) { "candidate datasource has already been transferred" }
        transferred = true
        return dataSource
    }

    override fun close() {
        if (!transferred) SwitchableDataSource.closeDataSource(dataSource)
    }
}
