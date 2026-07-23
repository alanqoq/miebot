package com.mieai.qqbot.onebot11.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.QqMessageSendResult;
import com.mieai.qqbot.client.QqOpenApiClient;
import com.mieai.qqbot.client.QqTextMessageRequest;
import com.mieai.qqbot.domain.bot.BotDefinition;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.persistence.bot.StoredBot;
import com.mieai.qqbot.runtime.openapi.BotOpenApiClientProvider;
import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityIdRepository;
import com.mieai.qqbot.onebot11.mapping.OneBotEntityType;
import com.mieai.qqbot.onebot11.mapping.OneBotMessageRepository;
import com.mieai.qqbot.onebot11.support.OneBotTestDatabase;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class OneBotActionServiceTest {
    @TempDir
    private Path directory;

    @Test
    void sendsMappedC2cMessagePreservesEchoAndIndexesResult() throws Exception {
        BotId botId = BotId.of(UUID.randomUUID());
        DataSource dataSource = OneBotTestDatabase.create(directory.resolve("actions.db"));
        OneBotTestDatabase.insertBot(dataSource, botId.toString());
        OneBotEntityIdRepository entityIds = new OneBotEntityIdRepository(dataSource);
        OneBotMessageRepository messages = new OneBotMessageRepository(dataSource);
        long userId = entityIds.aliasFor(botId, OneBotEntityType.USER, "", "user-openid");
        ObjectMapper mapper = new ObjectMapper();

        QqOpenApiClient client = mock(QqOpenApiClient.class);
        when(client.sendText(any())).thenReturn(CompletableFuture.completedFuture(
                new QqMessageSendResult("official-message", 1, "2026-07-22T08:00:00Z")));
        BotOpenApiClientProvider clients = mock(BotOpenApiClientProvider.class);
        when(clients.clientFor(botId)).thenReturn(client);
        BotRepository bots = mock(BotRepository.class);
        StoredBot storedBot = mock(StoredBot.class);
        BotDefinition definition = mock(BotDefinition.class);
        when(definition.displayName()).thenReturn("Test Bot");
        when(storedBot.definition()).thenReturn(definition);
        when(bots.findById(botId)).thenReturn(Optional.of(storedBot));
        OneBotEventMapper events = mock(OneBotEventMapper.class);
        when(events.selfId(botId)).thenReturn(10L);

        try (OneBotActionService service = new OneBotActionService(
                mapper, clients, bots, mock(BotSupervisor.class), entityIds, messages,
                new OneBotMessageCodec(mapper),
                new OneBotMediaCache(directory.resolve("cache")), events)) {
            String response = service.handle(botId, """
                    {"action":"send_private_msg","params":{
                      "user_id":%d,"message":[{"type":"text","data":{"text":"hello"}}]
                    },"echo":{"request":7}}
                    """.formatted(userId)).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var json = mapper.readTree(response);

            assertThat(json.path("status").asText()).isEqualTo("ok");
            assertThat(json.path("echo").path("request").asInt()).isEqualTo(7);
            int messageId = json.path("data").path("message_id").asInt();
            assertThat(messageId).isPositive();
            assertThat(messages.find(botId, messageId)).get()
                    .extracting(value -> value.officialMessageId())
                    .isEqualTo("official-message");

            ArgumentCaptor<QqTextMessageRequest> request =
                    ArgumentCaptor.forClass(QqTextMessageRequest.class);
            verify(client).sendText(request.capture());
            assertThat(request.getValue().targetId()).isEqualTo("user-openid");
            assertThat(request.getValue().content()).isEqualTo("hello");
        }
    }

    @Test
    void returnsExplicitFailureForUnsupportedActions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (OneBotActionService service = new OneBotActionService(
                mapper,
                mock(BotOpenApiClientProvider.class),
                mock(BotRepository.class),
                mock(BotSupervisor.class),
                mock(OneBotEntityIdRepository.class),
                mock(OneBotMessageRepository.class),
                new OneBotMessageCodec(mapper),
                new OneBotMediaCache(directory.resolve("cache")),
                mock(OneBotEventMapper.class))) {
            for (String action : new String[] {
                    "set_group_kick",
                    "set_group_kick_async",
                    "set_group_kick_rate_limited"
            }) {
                String response = service.handle(BotId.of(UUID.randomUUID()),
                        "{\"action\":\"" + action + "\",\"echo\":\"same\"}")
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                var json = mapper.readTree(response);
                assertThat(json.path("status").asText()).as(action).isEqualTo("failed");
                assertThat(json.path("retcode").asInt()).as(action).isEqualTo(1404);
                assertThat(json.path("echo").asText()).as(action).isEqualTo("same");
            }
        }
    }
}
