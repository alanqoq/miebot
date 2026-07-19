package com.mieai.qqbot.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.protocol.auth.AccessTokenRequest;
import com.mieai.qqbot.protocol.auth.AccessTokenResponse;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import org.junit.jupiter.api.Test;

class AccessTokenContractTest {
    private final JsonCodec codec = JsonCodecs.defaultCodec();

    @Test
    void decodesAndEncodesOfficialRequestShape() {
        AccessTokenRequest request = codec.decode(
                FixtureLoader.load("/fixtures/auth/access-token-request.json"), AccessTokenRequest.class);

        assertThat(request.appId()).isEqualTo("102012345");
        assertThat(request.clientSecret()).isEqualTo("CLIENT_SECRET_VALUE");
        assertThat(codec.encode(request))
                .contains("\"appId\":\"102012345\"")
                .contains("\"clientSecret\":\"CLIENT_SECRET_VALUE\"")
                .doesNotContain("future_field");
    }

    @Test
    void acceptsStringExpiryAndIgnoresUnknownResponseFields() {
        AccessTokenResponse response = codec.decode(
                FixtureLoader.load("/fixtures/auth/access-token-response.json"), AccessTokenResponse.class);

        assertThat(response.accessToken()).isEqualTo("ACCESS_TOKEN_VALUE");
        assertThat(response.expiresIn()).isEqualTo(7200L);
        assertThat(response.code()).isNull();
        assertThat(response.message()).isNull();
    }

    @Test
    void decodesBusinessErrorWithoutRenderingRemoteMessage() {
        AccessTokenResponse response = codec.decode(
                FixtureLoader.load("/fixtures/auth/access-token-business-error.json"),
                AccessTokenResponse.class);

        assertThat(response.accessToken()).isNull();
        assertThat(response.expiresIn()).isZero();
        assertThat(response.code()).isEqualTo(11241);
        assertThat(response.message()).contains("remote-sensitive-detail");
        assertThat(response.toString())
                .contains("code=11241")
                .contains("message=<redacted>")
                .doesNotContain("remote-sensitive-detail");
    }

    @Test
    void redactsSecretsAndTokensFromStringRepresentations() {
        AccessTokenRequest request = new AccessTokenRequest("102012345", "CLIENT_SECRET_VALUE");
        AccessTokenResponse response = new AccessTokenResponse("ACCESS_TOKEN_VALUE", 7200);

        assertThat(request.toString())
                .contains("clientSecret=<redacted>")
                .doesNotContain("CLIENT_SECRET_VALUE");
        assertThat(response.toString())
                .contains("accessToken=<redacted>")
                .doesNotContain("ACCESS_TOKEN_VALUE");
    }
}
