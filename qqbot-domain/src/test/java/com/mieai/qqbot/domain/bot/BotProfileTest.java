package com.mieai.qqbot.domain.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BotProfileTest {
    @Test
    void supportsProfileWithoutAvatar() {
        BotProfile profile = new BotProfile("123456789", "Support Bot");

        assertThat(profile.platformUserId()).isEqualTo("123456789");
        assertThat(profile.displayName()).isEqualTo("Support Bot");
        assertThat(profile.avatarUrl()).isEmpty();
    }

    @Test
    void supportsAbsoluteHttpAndHttpsAvatarUrls() {
        URI httpsAvatar = URI.create("https://q.qlogo.cn/avatar.png?size=100");

        BotProfile profile = new BotProfile("123456789", "Support Bot", httpsAvatar);

        assertThat(profile.avatarUrl()).contains(httpsAvatar);
        assertThat(new BotProfile("123456789", "Support Bot", URI.create("http://example.test/avatar.png"))
                .avatarUrl()).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 123456789", "1234 56789", "1234\t56789"})
    void rejectsInvalidPlatformUserId(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new BotProfile(value, "Support Bot"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " Support Bot", "Support Bot ", "Support\nBot"})
    void rejectsInvalidDisplayName(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new BotProfile("123456789", value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/avatar.png", "mailto:avatar@example.test", "ftp://example.test/avatar.png", "https:avatar.png"})
    void rejectsUnsupportedAvatarUrl(String value) {
        URI avatarUrl = URI.create(value);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new BotProfile("123456789", "Support Bot", avatarUrl));
    }

    @Test
    void rejectsNullRequiredValues() {
        assertThatNullPointerException().isThrownBy(() -> new BotProfile(null, "Support Bot"));
        assertThatNullPointerException().isThrownBy(() -> new BotProfile("123456789", null));
        assertThatNullPointerException()
                .isThrownBy(() -> new BotProfile("123456789", "Support Bot", (Optional<URI>) null));
        assertThatNullPointerException()
                .isThrownBy(() -> new BotProfile("123456789", "Support Bot", (URI) null));
    }
}
