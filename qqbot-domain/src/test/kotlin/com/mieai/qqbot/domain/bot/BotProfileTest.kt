package com.mieai.qqbot.domain.bot

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

class BotProfileTest {
    @Test
    fun supportsProfileWithoutAvatar() {
        val profile = BotProfile("123456789", "Support Bot")

        assertThat(profile.platformUserId).isEqualTo("123456789")
        assertThat(profile.displayName).isEqualTo("Support Bot")
        assertThat(profile.avatarUrl).isNull()
    }

    @Test
    fun supportsAbsoluteHttpAndHttpsAvatarUrls() {
        val httpsAvatar = URI.create("https://q.qlogo.cn/avatar.png?size=100")
        val profile = BotProfile("123456789", "Support Bot", httpsAvatar)

        assertThat(profile.avatarUrl).isEqualTo(httpsAvatar)
        assertThat(BotProfile("123456789", "Support Bot", URI.create("http://example.test/avatar.png")).avatarUrl)
            .isEqualTo(URI.create("http://example.test/avatar.png"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " 123456789", "1234 56789", "1234\t56789"])
    fun rejectsInvalidPlatformUserId(value: String) {
        assertThatIllegalArgumentException().isThrownBy { BotProfile(value, "Support Bot") }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", " Support Bot", "Support Bot ", "Support\nBot"])
    fun rejectsInvalidDisplayName(value: String) {
        assertThatIllegalArgumentException().isThrownBy { BotProfile("123456789", value) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["/avatar.png", "mailto:avatar@example.test", "ftp://example.test/avatar.png", "https:avatar.png"],
    )
    fun rejectsUnsupportedAvatarUrl(value: String) {
        val avatarUrl = URI.create(value)
        assertThatIllegalArgumentException().isThrownBy { BotProfile("123456789", "Support Bot", avatarUrl) }
    }
}
