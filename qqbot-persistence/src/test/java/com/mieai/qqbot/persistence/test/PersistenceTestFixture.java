package com.mieai.qqbot.persistence.test;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.JdbcBotRepository;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

public final class PersistenceTestFixture {
    public static final Instant BASE_TIME = Instant.parse("2026-07-16T12:00:00Z");

    private PersistenceTestFixture() {
    }

    public static DataSource migratedDatabase(Path databaseFile) {
        DataSource dataSource = SQLiteDataSourceFactory.create(databaseFile);
        SQLiteDatabaseInitializer.migrate(dataSource);
        return dataSource;
    }

    public static StoredBot insertBot(
            DataSource dataSource,
            String id,
            String appId,
            BotEnvironment environment) {
        StoredBot bot = new StoredBot(
                new BotDefinition(
                        BotId.of(UUID.fromString(id)),
                        "Bot " + appId,
                        QqAppId.of(appId),
                        environment,
                        GatewayIntents.of(512L),
                        ShardSpec.single(),
                        true,
                        BotRevision.initial(),
                        BASE_TIME,
                        BASE_TIME),
                SecretCiphertext.of("v1:ciphertext-" + appId, "master-key-v1"));
        new JdbcBotRepository(dataSource).insert(bot);
        return bot;
    }
}
