package com.mieai.qqbot.protocol

import com.mieai.qqbot.protocol.error.QqApiError
import com.mieai.qqbot.protocol.gateway.GatewayUrlResponse
import com.mieai.qqbot.protocol.json.JsonCodecs
import com.mieai.qqbot.protocol.user.CurrentBotUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiContractTest {
    private val codec = JsonCodecs.defaultCodec()

    @Test
    fun decodesQqErrorPayloadAndIgnoresExtensions() {
        val error = codec.decode(
            FixtureLoader.load("/fixtures/error/qq-api-error.json"),
            QqApiError::class.java,
        )

        assertThat(error.code).isEqualTo(40034005)
        assertThat(error.message).isEqualTo("request parameter error")
        assertThat(error.traceId).isEqualTo("65c2f1f7d9c9490e8f2f6f1d56ac1abc")
    }

    @Test
    fun decodesCurrentBotUserShape() {
        val bot = codec.decode(
            FixtureLoader.load("/fixtures/user/current-bot.json"),
            CurrentBotUser::class.java,
        )

        assertThat(bot.id).isEqualTo("123456789012345678")
        assertThat(bot.username).isEqualTo("ExampleBot")
        assertThat(bot.avatar).isEqualTo("https://thirdqq.qlogo.cn/example/640")
        assertThat(bot.bot).isTrue()
        assertThat(bot.unionOpenid).isEqualTo("UNION_OPENID")
        assertThat(bot.unionUserAccount).isEqualTo("UNION_USER_ACCOUNT")
    }

    @Test
    fun decodesGatewayDiscoveryResponse() {
        val gateway = codec.decode(
            FixtureLoader.load("/fixtures/gateway/gateway-url.json"),
            GatewayUrlResponse::class.java,
        )

        assertThat(gateway.url).isEqualTo("wss://api.sgroup.qq.com/websocket/")
    }
}
