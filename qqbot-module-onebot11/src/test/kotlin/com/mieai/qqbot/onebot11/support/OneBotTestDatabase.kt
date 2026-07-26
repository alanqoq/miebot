package com.mieai.qqbot.onebot11.support

import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path
import javax.sql.DataSource

object OneBotTestDatabase {
    fun create(file: Path): DataSource {
        val dataSource = SQLiteDataSourceFactory.create(file)
        JdbcTemplate(dataSource).execute("CREATE TABLE bots (id TEXT NOT NULL PRIMARY KEY) STRICT")
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:META-INF/qqbot/modules/onebot11/db/sqlite")
            .table("onebot_test_flyway")
            .baselineOnMigrate(true)
            .baselineVersion(MigrationVersion.fromVersion("0"))
            .load()
            .migrate()
        return dataSource
    }

    fun insertBot(dataSource: DataSource, botId: String) {
        JdbcTemplate(dataSource).update("INSERT INTO bots(id) VALUES (?)", botId)
    }
}
