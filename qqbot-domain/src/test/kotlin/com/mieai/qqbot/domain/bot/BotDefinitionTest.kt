package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

class BotDefinitionTest {
    @Test
    fun createsCredentialFreeBotConfiguration() {
        val definition = validDefinition()

        assertThat(definition.id.toString()).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
        assertThat(definition.displayName).isEqualTo("Support Bot")
        assertThat(definition.appId).isEqualTo(QqAppId.of("1029384756"))
        assertThat(definition.environment).isEqualTo(BotEnvironment.SANDBOX)
        assertThat(definition.intents).isEqualTo(GatewayIntents.of(512L))
        assertThat(definition.shardSpec).isEqualTo(ShardSpec.single())
        assertThat(definition.enabled).isTrue()
        assertThat(definition.revision).isEqualTo(BotRevision.initial())
        assertThat(definition.createdAt).isEqualTo(CREATED_AT)
        assertThat(definition.updatedAt).isEqualTo(UPDATED_AT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " Support Bot", "Support Bot ", "Support\nBot"])
    fun rejectsInvalidDisplayName(displayName: String) {
        assertThatIllegalArgumentException().isThrownBy { definitionWithName(displayName) }
    }

    @Test
    fun allowsCreationAndUpdateAtTheSameInstant() {
        val definition = BotDefinition(
            botId(), "Support Bot", appId(), BotEnvironment.PRODUCTION,
            GatewayIntents.NONE, ShardSpec.single(), false, BotRevision.initial(), CREATED_AT, CREATED_AT,
        )

        assertThat(definition.updatedAt).isEqualTo(CREATED_AT)
    }

    @Test
    fun rejectsUpdateBeforeCreation() {
        assertThatIllegalArgumentException().isThrownBy {
            BotDefinition(
                botId(), "Support Bot", appId(), BotEnvironment.PRODUCTION,
                GatewayIntents.NONE, ShardSpec.single(), false, BotRevision.initial(),
                CREATED_AT, CREATED_AT.minusSeconds(1),
            )
        }
    }

    private fun validDefinition() = BotDefinition(
        botId(), "Support Bot", appId(), BotEnvironment.SANDBOX,
        GatewayIntents.of(512L), ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT,
    )

    private fun definitionWithName(displayName: String) = BotDefinition(
        botId(), displayName, appId(), BotEnvironment.SANDBOX,
        GatewayIntents.NONE, ShardSpec.single(), true, BotRevision.initial(), CREATED_AT, UPDATED_AT,
    )

    private fun botId() = BotId.of(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))

    private fun appId() = QqAppId.of("1029384756")

    companion object {
        private val CREATED_AT = Instant.parse("2026-07-16T12:00:00Z")
        private val UPDATED_AT = Instant.parse("2026-07-16T12:05:00Z")
    }
}
