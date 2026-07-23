package com.mieai.qqbot.admin.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mieai.qqbot.admin.error.ApiExceptionHandler;
import com.mieai.qqbot.admin.plugins.PluginAdministrationException;
import com.mieai.qqbot.admin.security.AdminSecurityConfiguration;
import com.mieai.qqbot.admin.web.TraceIdFilter;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.persistence.bot.OptimisticLockException;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.configuration.BotConfigurationView;
import com.mieai.qqbot.runtime.configuration.CreateBotCommand;
import com.mieai.qqbot.runtime.configuration.UpdateBotCommand;
import com.mieai.qqbot.runtime.security.KeyUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = BotController.class)
@ContextConfiguration(classes = {
    BotController.class,
    ApiExceptionHandler.class,
    AdminSecurityConfiguration.class,
    TraceIdFilter.class
})
class BotControllerMockMvcTest {
    private static final UUID BOT_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final BotId BOT_ID = BotId.of(BOT_UUID);
    private static final Instant CREATED_AT = Instant.parse("2026-07-16T12:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-16T12:05:00Z");
    private static final String CREATE_SECRET = "create-secret-123";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BotConfigurationService service;

    @Test
    void rejectsUnauthenticatedWriteRequest() throws Exception {
        mockMvc.perform(post("/api/bots")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void createsBotWithLocationAndEtagWithoutReturningAppSecret() throws Exception {
        when(service.create(any(CreateBotCommand.class))).thenReturn(view(true, 1L));

        mockMvc.perform(post("/api/bots")
                        .with(csrf())
                        .header(TraceIdFilter.HEADER_NAME, "create-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/bots/" + BOT_UUID))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string(TraceIdFilter.HEADER_NAME, "create-trace"))
                .andExpect(jsonPath("$.id").value(BOT_UUID.toString()))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.secretConfigured").value(true))
                .andExpect(jsonPath("$.appSecret").doesNotExist())
                .andExpect(content().string(not(containsString(CREATE_SECRET))))
                .andExpect(content().string(not(containsString("appSecret"))));

        ArgumentCaptor<CreateBotCommand> command = ArgumentCaptor.forClass(CreateBotCommand.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().displayName()).isEqualTo("Support Bot");
        assertThat(command.getValue().appId()).isEqualTo(QqAppId.of("1029384756"));
        assertThat(command.getValue().environment()).isEqualTo(BotEnvironment.PRODUCTION);
        assertThat(command.getValue().shardSpec()).isEqualTo(new ShardSpec(0, 1));
        assertThat(command.getValue().appSecret().isDestroyed()).isTrue();
        assertThat(command.getValue().appSecret().toString()).doesNotContain(CREATE_SECRET);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void listsBotsWithoutSecretMaterial() throws Exception {
        when(service.findAll()).thenReturn(List.of(view(true, 1L)));

        mockMvc.perform(get("/api/bots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(BOT_UUID.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Support Bot"))
                .andExpect(jsonPath("$[0].secretConfigured").value(true))
                .andExpect(jsonPath("$[0].appSecret").doesNotExist())
                .andExpect(content().string(not(containsString("appSecret"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void getsBotDetailWithRevisionEtag() throws Exception {
        when(service.findById(BOT_ID)).thenReturn(Optional.of(view(true, 4L)));

        mockMvc.perform(get("/api/bots/{botId}", BOT_UUID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.id").value(BOT_UUID.toString()))
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.environment").value("PRODUCTION"))
                .andExpect(jsonPath("$.appSecret").doesNotExist());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updatesEditableFieldsAndKeepsSecretOmitted() throws Exception {
        when(service.update(any(UpdateBotCommand.class))).thenReturn(updatedView(2L));

        mockMvc.perform(put("/api/bots/{botId}", BOT_UUID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.displayName").value("Updated Bot"))
                .andExpect(jsonPath("$.appId").value("9988776655"))
                .andExpect(jsonPath("$.environment").value("SANDBOX"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.appSecret").doesNotExist());

        ArgumentCaptor<UpdateBotCommand> command = ArgumentCaptor.forClass(UpdateBotCommand.class);
        verify(service).update(command.capture());
        assertThat(command.getValue().botId()).isEqualTo(BOT_ID);
        assertThat(command.getValue().expectedRevision()).isEqualTo(BotRevision.initial());
        assertThat(command.getValue().displayName()).isEqualTo("Updated Bot");
        assertThat(command.getValue().environment()).isEqualTo(BotEnvironment.SANDBOX);
        assertThat(command.getValue().appSecret()).isEmpty();
        assertThat(command.getValue().maxMediaUploadBytes()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @WithMockUser(username = "admin", roles = "ADMIN")
    void enablesAndDisablesBot(boolean enabled) throws Exception {
        BotConfigurationView response = view(enabled, 3L);
        if (enabled) {
            when(service.enable(BOT_ID, BotRevision.of(2L))).thenReturn(response);
        } else {
            when(service.disable(BOT_ID, BotRevision.of(2L))).thenReturn(response);
        }

        mockMvc.perform(patch("/api/bots/{botId}/enabled", BOT_UUID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"enabled\":" + enabled + "}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.enabled").value(enabled))
                .andExpect(jsonPath("$.revision").value(3));

        if (enabled) {
            verify(service).enable(BOT_ID, BotRevision.of(2L));
            verify(service, never()).disable(any(), any());
        } else {
            verify(service).disable(BOT_ID, BotRevision.of(2L));
            verify(service, never()).enable(any(), any());
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void deletesBotOnlyAfterAuthenticatedMutationRequest() throws Exception {
        mockMvc.perform(delete("/api/bots/{botId}", BOT_UUID).with(csrf()))
                .andExpect(status().isNoContent());
        verify(service).delete(BOT_ID);
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void reportsPluginDataCleanupFailureInsteadOfReturningNoContent() throws Exception {
        doThrow(new PluginAdministrationException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "BOT_PLUGIN_DATA_DELETE_FAILED",
                        "Unable to delete bot plugin data tombstone"))
                .when(service).delete(BOT_ID);

        mockMvc.perform(delete("/api/bots/{botId}", BOT_UUID).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("BOT_PLUGIN_DATA_DELETE_FAILED"))
                .andExpect(jsonPath("$.message").value("Unable to delete bot plugin data tombstone"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void mapsOptimisticLockFailureToConflict() throws Exception {
        when(service.update(any(UpdateBotCommand.class)))
                .thenThrow(new OptimisticLockException(BOT_ID, BotRevision.initial()));

        mockMvc.perform(put("/api/bots/{botId}", BOT_UUID)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(containsString("expected revision 1")))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(content().string(not(containsString("appSecret"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void mapsUnavailableKeyToSanitizedServiceUnavailableResponse() throws Exception {
        when(service.create(any(CreateBotCommand.class))).thenThrow(new KeyUnavailableException());

        mockMvc.perform(post("/api/bots")
                        .with(csrf())
                        .header(TraceIdFilter.HEADER_NAME, "key-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("APP_SECRET_KEY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("AppSecret master key is not configured"))
                .andExpect(jsonPath("$.traceId").value("key-trace"))
                .andExpect(content().string(not(containsString(CREATE_SECRET))))
                .andExpect(content().string(not(containsString("KeyUnavailableException"))))
                .andExpect(content().string(not(containsString("stackTrace"))));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void returnsFieldErrorsForInvalidCreateRequest() throws Exception {
        String invalid = """
                {
                  "displayName": "",
                  "appId": "",
                  "environment": "PRODUCTION",
                  "intents": 0,
                  "shardIndex": 0,
                  "shardCount": 0,
                  "enabled": true,
                  "appSecret": ""
                }
                """;

        mockMvc.perform(post("/api/bots")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.displayName").exists())
                .andExpect(jsonPath("$.fieldErrors.appId").exists())
                .andExpect(jsonPath("$.fieldErrors.shardCount").exists())
                .andExpect(jsonPath("$.fieldErrors.appSecret").exists());

        verifyNoInteractions(service);
    }

    private static BotConfigurationView view(boolean enabled, long revision) {
        return new BotConfigurationView(
                BOT_ID,
                "Support Bot",
                QqAppId.of("1029384756"),
                BotEnvironment.PRODUCTION,
                GatewayIntents.of(1L << 25),
                ShardSpec.single(),
                enabled,
                BotRevision.of(revision),
                CREATED_AT,
                UPDATED_AT,
                true);
    }

    private static BotConfigurationView updatedView(long revision) {
        return new BotConfigurationView(
                BOT_ID,
                "Updated Bot",
                QqAppId.of("9988776655"),
                BotEnvironment.SANDBOX,
                GatewayIntents.of(1L << 26),
                new ShardSpec(1, 2),
                true,
                BotRevision.of(revision),
                CREATED_AT,
                UPDATED_AT.plusSeconds(60),
                true);
    }

    private static String validCreateJson() {
        return """
                {
                  "displayName": "Support Bot",
                  "appId": "1029384756",
                  "environment": "PRODUCTION",
                  "intents": 33554432,
                  "shardIndex": 0,
                  "shardCount": 1,
                  "enabled": true,
                  "appSecret": "%s"
                }
                """.formatted(CREATE_SECRET);
    }

    private static String validUpdateJson() {
        return """
                {
                  "expectedRevision": 1,
                  "displayName": "Updated Bot",
                  "appId": "9988776655",
                  "environment": "SANDBOX",
                  "intents": 67108864,
                  "shardIndex": 1,
                  "shardCount": 2
                }
                """;
    }
}
