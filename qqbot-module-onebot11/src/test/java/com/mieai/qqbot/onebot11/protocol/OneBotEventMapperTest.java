package com.mieai.qqbot.onebot11.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.gateway.GatewayDispatch;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.event.BotGatewayEvent;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository;
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OneBotEventMapperTest {
    @TempDir
    private Path directory;

    @Test
    void mapsC2cAndOrdinaryGroupMessagesButNotChannelMessages() {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("events.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        BotRepository bots = mock(BotRepository.class);
        StoredBot stored = mock(StoredBot.class);
        BotDefinition definition = mock(BotDefinition.class);
        when(definition.appId()).thenReturn(QqAppId.of("123456"));
        when(stored.definition()).thenReturn(definition);
        when(bots.findById(botId)).thenReturn(Optional.of(stored));
        ObjectMapper objectMapper = new ObjectMapper();
        OneBotEventMapper mapper = new OneBotEventMapper(
                objectMapper,
                bots,
                new OneBotEntityIdRepository(dataSource),
                new OneBotMessageRepository(dataSource),
                new OneBotMessageCodec(objectMapper));

        var privateEvent = mapper.map(event(botId, "C2C_MESSAGE_CREATE", """
                {"op":0,"s":1,"t":"C2C_MESSAGE_CREATE","id":"event-1","d":{
                  "id":"message-1","content":"hello","timestamp":"2026-07-22T08:00:00Z",
                  "author":{"user_openid":"user-openid","username":"Alice"}
                }}
                """)).orElseThrow();
        assertThat(privateEvent.path("post_type").asText()).isEqualTo("message");
        assertThat(privateEvent.path("message_type").asText()).isEqualTo("private");
        assertThat(privateEvent.path("sender").path("nickname").asText()).isEqualTo("Alice");
        assertThat(privateEvent.path("message_id").asInt()).isPositive();

        var groupEvent = mapper.map(event(botId, "GROUP_AT_MESSAGE_CREATE", """
                {"op":0,"s":2,"t":"GROUP_AT_MESSAGE_CREATE","id":"event-2","d":{
                  "id":"message-2","group_openid":"group-openid","content":"group hello",
                  "timestamp":"2026-07-22T08:01:00Z",
                  "author":{"member_openid":"member-openid","username":"Bob"}
                }}
                """)).orElseThrow();
        assertThat(groupEvent.path("message_type").asText()).isEqualTo("group");
        assertThat(groupEvent.path("group_id").asLong()).isPositive();

        assertThat(mapper.map(event(botId, "AT_MESSAGE_CREATE", """
                {"op":0,"s":3,"t":"AT_MESSAGE_CREATE","id":"event-3","d":{
                  "id":"channel-message","channel_id":"channel","guild_id":"guild",
                  "content":"ignored","author":{"id":"guild-user"}
                }}
                """))).isEmpty();
    }

    private static BotGatewayEvent event(BotId botId, String type, String payload) {
        return new BotGatewayEvent(
                botId, new GatewayDispatch(1L, type, payload), Instant.parse("2026-07-22T08:00:00Z"));
    }
}
