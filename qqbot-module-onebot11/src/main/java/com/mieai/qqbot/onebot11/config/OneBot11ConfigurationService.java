package com.mieai.qqbot.onebot11.config;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.security.AesGcmConfigurationSecretCipher;
import com.mieai.qqbot.runtime.security.EncryptedConfigurationValue;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class OneBot11ConfigurationService {
    private static final int MAX_TOKEN_LENGTH = 4_096;

    private final OneBot11ConfigRepository configs;
    private final BotRepository bots;
    private final AesGcmConfigurationSecretCipher cipher;
    private final Clock clock;

    public OneBot11ConfigurationService(
            OneBot11ConfigRepository configs,
            BotRepository bots,
            AesGcmConfigurationSecretCipher cipher) {
        this(configs, bots, cipher, Clock.systemUTC());
    }

    OneBot11ConfigurationService(
            OneBot11ConfigRepository configs,
            BotRepository bots,
            AesGcmConfigurationSecretCipher cipher,
            Clock clock) {
        this.configs = Objects.requireNonNull(configs, "configs must not be null");
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public OneBot11SettingsView get(BotId botId) {
        requireBot(botId);
        Instant now = clock.instant();
        return OneBot11SettingsView.from(
                configs.find(botId).orElseGet(() -> OneBot11Config.defaults(botId, now)));
    }

    public OneBot11SettingsView update(BotId botId, OneBot11SettingsRequest request) {
        requireBot(botId);
        Objects.requireNonNull(request, "request must not be null");
        OneBot11Config existing = configs.find(botId)
                .orElseGet(() -> OneBot11Config.defaults(botId, clock.instant()));
        if (request.expectedRevision() != existing.revision()) {
            throw new OneBot11RevisionConflictException();
        }
        if (request.clearAccessToken() && normalizedToken(request.accessToken()) != null) {
            throw new IllegalArgumentException("accessToken and clearAccessToken cannot be combined");
        }

        String bindAddress = requireBindAddress(request.forwardBindAddress());
        OptionalInt forwardPort = request.forwardPort() == null
                ? OptionalInt.empty() : OptionalInt.of(request.forwardPort());
        Optional<URI> reverseUrl = parseReverseUrl(request.reverseUrl());
        validateTransportSettings(request, forwardPort, reverseUrl);

        Optional<EncryptedConfigurationValue> encryptedToken = existing.encryptedAccessToken();
        if (request.clearAccessToken()) {
            encryptedToken = Optional.empty();
        } else {
            String replacement = normalizedToken(request.accessToken());
            if (replacement != null) {
                char[] characters = replacement.toCharArray();
                try {
                    encryptedToken = Optional.of(cipher.encrypt(characters, tokenPurpose(botId)));
                } finally {
                    Arrays.fill(characters, '\0');
                }
            }
        }
        if (request.enabled() && (request.forwardEnabled() || request.reverseEnabled())
                && encryptedToken.isEmpty()) {
            throw new IllegalArgumentException(
                    "An access token is required when a OneBot transport is enabled");
        }
        if (request.enabled() && request.forwardEnabled()
                && configs.forwardPortUsedByOther(botId, forwardPort.orElseThrow())) {
            throw new IllegalArgumentException("forwardPort is already used by another bot");
        }

        Instant now = clock.instant();
        OneBot11Config desired = new OneBot11Config(
                botId,
                request.enabled(),
                request.forwardEnabled(),
                bindAddress,
                forwardPort,
                request.reverseEnabled(),
                reverseUrl,
                encryptedToken,
                request.heartbeatEnabled(),
                request.heartbeatIntervalMs(),
                request.reconnectIntervalMs(),
                existing.revision(),
                existing.createdAt(),
                now);
        return OneBot11SettingsView.from(configs.save(desired, existing.revision()));
    }

    public Optional<ResolvedOneBot11Config> resolve(BotId botId) {
        return configs.find(botId).filter(OneBot11Config::enabled).map(this::resolve);
    }

    public List<ResolvedOneBot11Config> resolveEnabled() {
        return configs.findEnabled().stream().map(this::resolve).toList();
    }

    public List<BotId> enabledBotIds() {
        return configs.findEnabled().stream().map(OneBot11Config::botId).toList();
    }

    public boolean isEnabled(BotId botId) {
        return configs.find(botId).map(OneBot11Config::enabled).orElse(false);
    }

    private ResolvedOneBot11Config resolve(OneBot11Config config) {
        EncryptedConfigurationValue encrypted = config.encryptedAccessToken()
                .orElseThrow(() -> new IllegalStateException(
                        "Enabled OneBot settings have no access token"));
        char[] decrypted = cipher.decrypt(encrypted, tokenPurpose(config.botId()));
        try {
            return new ResolvedOneBot11Config(config, new String(decrypted));
        } finally {
            Arrays.fill(decrypted, '\0');
        }
    }

    private void requireBot(BotId botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        if (bots.findById(botId).isEmpty()) {
            throw new BotNotFoundException(botId);
        }
    }

    private static void validateTransportSettings(
            OneBot11SettingsRequest request,
            OptionalInt forwardPort,
            Optional<URI> reverseUrl) {
        if (request.expectedRevision() < 0L) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        if (request.enabled() && !request.forwardEnabled() && !request.reverseEnabled()) {
            throw new IllegalArgumentException("At least one OneBot transport must be enabled");
        }
        if (request.forwardEnabled()
                && (forwardPort.isEmpty() || forwardPort.getAsInt() < 1
                        || forwardPort.getAsInt() > 65_535)) {
            throw new IllegalArgumentException("forwardPort is required and must be valid");
        }
        if (request.reverseEnabled() && reverseUrl.isEmpty()) {
            throw new IllegalArgumentException("reverseUrl is required");
        }
        if (request.heartbeatIntervalMs() < 1_000
                || request.heartbeatIntervalMs() > 300_000) {
            throw new IllegalArgumentException("heartbeatIntervalMs is invalid");
        }
        if (request.reconnectIntervalMs() < 500
                || request.reconnectIntervalMs() > 300_000) {
            throw new IllegalArgumentException("reconnectIntervalMs is invalid");
        }
    }

    private static String requireBindAddress(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.length() > 255
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("forwardBindAddress is invalid");
        }
        return value;
    }

    private static Optional<URI> parseReverseUrl(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (!value.equals(value.strip()) || value.length() > 2_048) {
            throw new IllegalArgumentException("reverseUrl is invalid");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("reverseUrl is invalid", exception);
        }
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || uri.getHost() == null || scheme == null
                || !(scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss"))
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("reverseUrl must be an absolute ws or wss URL");
        }
        return Optional.of(uri);
    }

    private static String normalizedToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!value.equals(value.strip()) || value.length() > MAX_TOKEN_LENGTH
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("accessToken is invalid");
        }
        return value;
    }

    private static String tokenPurpose(BotId botId) {
        return "onebot11-access-token:" + botId;
    }
}
