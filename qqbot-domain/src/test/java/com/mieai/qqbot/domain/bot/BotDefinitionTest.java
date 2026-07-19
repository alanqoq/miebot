package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BotDefinitionTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-16T12:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-07-16T12:05:00Z");

    @Test
    void createsCredentialFreeBotConfiguration() {
        BotDefinition definition = validDefinition();

        assertThat(definition.id().toString()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(definition.displayName()).isEqualTo("Support Bot");
        assertThat(definition.appId()).isEqualTo(QqAppId.of("1029384756"));
        assertThat(definition.environment()).isEqualTo(BotEnvironment.SANDBOX);
        assertThat(definition.intents()).isEqualTo(GatewayIntents.of(512L));
        assertThat(definition.shardSpec()).isEqualTo(ShardSpec.single());
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.revision()).isEqualTo(BotRevision.initial());
        assertThat(definition.createdAt()).isEqualTo(CREATED_AT);
        assertThat(definition.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " Support Bot", "Support Bot ", "Support\nBot"})
    void rejectsInvalidDisplayName(String displayName) {
        assertThatIllegalArgumentException().isThrownBy(() -> definitionWithName(displayName));
    }

    @Test
    void allowsCreationAndUpdateAtTheSameInstant() {
        BotDefinition definition = new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.PRODUCTION,
                GatewayIntents.NONE, ShardSpec.single(), false, BotRevision.initial(), CREATED_AT, CREATED_AT);

        assertThat(definition.updatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void rejectsUpdateBeforeCreation() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.PRODUCTION,
                GatewayIntents.NONE, ShardSpec.single(), false, BotRevision.initial(),
                CREATED_AT, CREATED_AT.minusSeconds(1)));
    }

    @Test
    void rejectsNullRequiredFields() {
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                null, "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), null, appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", null, BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), null,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                null, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, null, true, BotRevision.initial(), CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, null, CREATED_AT, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), null, UPDATED_AT));
        assertThatNullPointerException().isThrownBy(() -> new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, null));
    }

    private static BotDefinition validDefinition() {
        return new BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
                GatewayIntents.of(512L), ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT);
    }

    private static BotDefinition definitionWithName(String displayName) {
        return new BotDefinition(
                botId(), displayName, appId(), BotEnvironment.SANDBOX,
                GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT);
    }

    private static BotId botId() {
        return BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    private static QqAppId appId() {
        return QqAppId.of("1029384756");
    }
}
