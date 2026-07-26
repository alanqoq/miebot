package com.mieai.qqbot.protocol

import com.mieai.qqbot.protocol.auth.AccessTokenRequest
import com.mieai.qqbot.protocol.auth.AccessTokenResponse
import com.mieai.qqbot.protocol.json.JsonCodecs
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AccessTokenContractTest {
    private val codec = JsonCodecs.defaultCodec()

    @Test
    fun decodesAndEncodesOfficialRequestShape() {
        val request = codec.decode(
            FixtureLoader.load("/fixtures/auth/access-token-request.json"),
            AccessTokenRequest::class.java,
        )

        assertThat(request.appId).isEqualTo("102012345")
        assertThat(request.clientSecret).isEqualTo("CLIENT_SECRET_VALUE")
        assertThat(codec.encode(request))
            .contains("\"appId\":\"102012345\"")
            .contains("\"clientSecret\":\"CLIENT_SECRET_VALUE\"")
            .doesNotContain("future_field")
    }

    @Test
    fun acceptsStringExpiryAndIgnoresUnknownResponseFields() {
        val response = codec.decode(
            FixtureLoader.load("/fixtures/auth/access-token-response.json"),
            AccessTokenResponse::class.java,
        )

        assertThat(response.accessToken).isEqualTo("ACCESS_TOKEN_VALUE")
        assertThat(response.expiresIn).isEqualTo(7200L)
        assertThat(response.code).isNull()
        assertThat(response.message).isNull()
    }

    @Test
    fun decodesBusinessErrorWithoutRenderingRemoteMessage() {
        val response = codec.decode(
            FixtureLoader.load("/fixtures/auth/access-token-business-error.json"),
            AccessTokenResponse::class.java,
        )

        assertThat(response.accessToken).isNull()
        assertThat(response.expiresIn).isZero()
        assertThat(response.code).isEqualTo(11241)
        assertThat(response.message).contains("remote-sensitive-detail")
        assertThat(response.toString())
            .contains("code=11241")
            .contains("message=<redacted>")
            .doesNotContain("remote-sensitive-detail")
    }

    @Test
    fun redactsSecretsAndTokensFromStringRepresentations() {
        val request = AccessTokenRequest("102012345", "CLIENT_SECRET_VALUE")
        val response = AccessTokenResponse("ACCESS_TOKEN_VALUE", 7200, null, null)

        assertThat(request.toString())
            .contains("clientSecret=<redacted>")
            .doesNotContain("CLIENT_SECRET_VALUE")
        assertThat(response.toString())
            .contains("accessToken=<redacted>")
            .doesNotContain("ACCESS_TOKEN_VALUE")
    }
}
