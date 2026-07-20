package com.mieai.qqbot.persistence.bot;

import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import org.springframework.jdbc.core.RowMapper;

final class StoredBotRowMapper implements RowMapper<StoredBot> {
    @Override
    public StoredBot mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        BotDefinition definition = new BotDefinition(
                BotId.parse(resultSet.getString("id")),
                resultSet.getString("display_name"),
                QqAppId.of(resultSet.getString("app_id")),
                BotEnvironment.valueOf(resultSet.getString("environment")),
                GatewayIntents.of(resultSet.getLong("intents")),
                new ShardSpec(resultSet.getInt("shard_index"), resultSet.getInt("shard_count")),
                resultSet.getInt("enabled") == 1,
                BotRevision.of(resultSet.getLong("revision")),
                Instant.parse(resultSet.getString("created_at")),
                Instant.parse(resultSet.getString("updated_at")),
                resultSet.getLong("max_media_upload_bytes"));
        SecretCiphertext appSecret = SecretCiphertext.of(
                resultSet.getString("app_secret_ciphertext"),
                resultSet.getString("app_secret_key_id"));
        return new StoredBot(definition, appSecret);
    }
}
