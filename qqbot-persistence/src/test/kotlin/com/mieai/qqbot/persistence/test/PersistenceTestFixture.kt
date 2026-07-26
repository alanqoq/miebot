package com.mieai.qqbot.persistence.test

import com.mieai.qqbot.domain.bot.BotDefinition
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.domain.bot.BotRevision
import com.mieai.qqbot.domain.bot.GatewayIntents
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.domain.bot.ShardSpec
import com.mieai.qqbot.persistence.bot.JdbcBotRepository
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.bot.StoredBot
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

object PersistenceTestFixture {
    val BASE_TIME: Instant = Instant.parse("2026-07-16T12:00:00Z")

    fun migratedDatabase(databaseFile: Path): DataSource {
        val dataSource = SQLiteDataSourceFactory.create(databaseFile)
        SQLiteDatabaseInitializer.migrate(dataSource)
        return dataSource
    }

    fun insertBot(
        dataSource: DataSource,
        id: String,
        appId: String,
        environment: BotEnvironment,
    ): StoredBot {
        val bot = StoredBot(
            BotDefinition(
                BotId.of(UUID.fromString(id)),
                "Bot $appId",
                QqAppId.of(appId),
                environment,
                GatewayIntents.of(512L),
                ShardSpec.single(),
                true,
                BotRevision.initial(),
                BASE_TIME,
                BASE_TIME,
            ),
            SecretCiphertext.of("v1:ciphertext-$appId", "master-key-v1"),
        )
        JdbcBotRepository(dataSource).insert(bot)
        return bot
    }
}
