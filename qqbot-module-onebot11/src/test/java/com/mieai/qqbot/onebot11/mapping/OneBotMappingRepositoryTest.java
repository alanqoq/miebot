package com.mieai.qqbot.onebot11.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneBotMappingRepositoryTest {
    @TempDir
    private Path directory;

    @Test
    void keepsEntityAliasesStableAndScopesGroupUsers() {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("mapping.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        OneBotEntityIdRepository ids = new OneBotEntityIdRepository(dataSource);

        long privateUser = ids.aliasFor(botId, OneBotEntityType.USER, "", "openid-1");
        long samePrivateUser = ids.aliasFor(botId, OneBotEntityType.USER, "", "openid-1");
        long groupUser = ids.aliasFor(botId, OneBotEntityType.USER, "group-1", "openid-1");

        assertThat(samePrivateUser).isEqualTo(privateUser);
        assertThat(groupUser).isNotEqualTo(privateUser);
        assertThat(ids.require(botId, groupUser, OneBotEntityType.USER).scopeId())
                .isEqualTo("group-1");
    }

    @Test
    void storesInt32MessageContextForLookupAndRecall() {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("messages.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        OneBotMessageRepository messages = new OneBotMessageRepository(dataSource);

        int messageId = messages.record(
                botId, "qq-message-1", QqMessageTargetType.GROUP, "group-openid",
                OneBotStoredMessage.Direction.INCOMING, "group", 1_721_632_000L, 42L,
                "[{\"type\":\"text\",\"data\":{\"text\":\"hello\"}}]",
                "{\"user_id\":42,\"nickname\":\"Alice\"}");

        assertThat(messageId).isPositive();
        assertThat(messages.find(botId, messageId)).get()
                .extracting(OneBotStoredMessage::officialMessageId,
                        OneBotStoredMessage::targetRawId)
                .containsExactly("qq-message-1", "group-openid");
        assertThat(messages.record(
                botId, "qq-message-1", QqMessageTargetType.GROUP, "group-openid",
                OneBotStoredMessage.Direction.INCOMING, "group", 1_721_632_000L, 42L,
                "[]", "{}"))
                .isEqualTo(messageId);
    }
}
