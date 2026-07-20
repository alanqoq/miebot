package com.mieai.qqbot.persistence.lease;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.plugin.BotPluginBinding;
import com.mieai.qqbot.persistence.plugin.JdbcBotPluginBindingRepository;
import com.mieai.qqbot.persistence.plugin.JdbcPluginArtifactRepository;
import com.mieai.qqbot.persistence.plugin.PluginArtifact;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcBotLeaseRepositoryTest {
    private static final String BOT = "550e8400-e29b-41d4-a716-446655440001";

    @TempDir Path directory;

    @Test
    void preventsLiveOwnerTakeoverAndFencesExpiredOwner() {
        var dataSource = migratedDatabase(directory.resolve("leases.db"));
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
        var repository = new JdbcBotLeaseRepository(dataSource);
        BotId botId = BotId.parse(BOT);
        Duration duration = Duration.ofSeconds(30);

        BotLease first = repository.acquire(botId, 0, "instance-a", BASE_TIME, duration).orElseThrow();
        assertThat(first.fencingToken()).isEqualTo(1);
        assertThat(repository.acquire(botId, 0, "instance-b", BASE_TIME.plusSeconds(1), duration)).isEmpty();
        assertThat(repository.renew(first, BASE_TIME.plusSeconds(5), duration)).isTrue();

        BotLease takeover = repository.acquire(
                botId, 0, "instance-b", BASE_TIME.plusSeconds(36), duration).orElseThrow();
        assertThat(takeover.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(repository.renew(first, BASE_TIME.plusSeconds(37), duration)).isFalse();
        assertThat(repository.release(first)).isFalse();
        assertThat(repository.release(takeover)).isTrue();
    }

    @Test
    void admitsOnlyInstancesWithTheRequiredPluginArtifactHash() {
        var dataSource = migratedDatabase(directory.resolve("plugin-hash-leases.db"));
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
        var artifactRepository = new JdbcPluginArtifactRepository(dataSource);
        artifactRepository.upsert(new PluginArtifact(
                "echo", "Echo", "1.0.0", "1.2.0", "echo.jar", "correct-hash",
                "com.example.Echo", "LOADED", true, BASE_TIME, BASE_TIME));
        new JdbcBotPluginBindingRepository(dataSource).insert(new BotPluginBinding(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440010"), "echo", BotId.parse(BOT),
                "{}", true, 0L, BASE_TIME, BASE_TIME));
        var repository = new JdbcBotLeaseRepository(dataSource);
        BotId botId = BotId.parse(BOT);
        Duration duration = Duration.ofSeconds(30);

        assertThat(repository.acquire(botId, 0, "instance-a", BASE_TIME, duration,
                Map.of("echo", "wrong-hash"))).isEmpty();
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isFalse();

        BotLease acquired = repository.acquire(botId, 0, "instance-a", BASE_TIME, duration,
                Map.of("echo", "correct-hash")).orElseThrow();
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isTrue();
        assertThat(repository.isOwned(botId, "instance-a", BASE_TIME.plusSeconds(1))).isTrue();
        assertThat(repository.isOwned(botId, "instance-b", BASE_TIME.plusSeconds(1))).isFalse();

        artifactRepository.upsert(new PluginArtifact(
                "echo", "Echo", "1.1.0", "1.2.0", "echo.jar", "replacement-hash",
                "com.example.Echo", "LOADED", true, BASE_TIME, BASE_TIME.plusSeconds(2)));
        assertThat(repository.renew(acquired, BASE_TIME.plusSeconds(5), duration,
                Map.of("echo", "correct-hash"))).isFalse();
        assertThat(repository.release(acquired)).isTrue();
    }

    @Test
    void rejectsAndStopsRenewingLeasesForANonCurrentShard() {
        var dataSource = migratedDatabase(directory.resolve("current-shard-leases.db"));
        insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
        var repository = new JdbcBotLeaseRepository(dataSource);
        BotId botId = BotId.parse(BOT);
        Duration duration = Duration.ofSeconds(30);
        BotLease oldShard = repository.acquire(botId, 0, "instance-a", BASE_TIME, duration)
                .orElseThrow();

        new JdbcTemplate(dataSource).update(
                "UPDATE bots SET shard_index=1, shard_count=2 WHERE id=?", BOT);

        assertThat(repository.renew(oldShard, BASE_TIME.plusSeconds(1), duration)).isFalse();
        assertThat(repository.isOwned(botId, 0, "instance-a", BASE_TIME.plusSeconds(1))).isFalse();
        assertThat(repository.isOwned(botId, "instance-a", BASE_TIME.plusSeconds(1))).isFalse();
        assertThat(repository.acquire(botId, 0, "instance-b", BASE_TIME.plusSeconds(1), duration))
                .isEmpty();

        BotLease current = repository.acquire(
                botId, 1, "instance-b", BASE_TIME.plusSeconds(1), duration).orElseThrow();
        assertThat(repository.isOwned(botId, 1, "instance-b", BASE_TIME.plusSeconds(2))).isTrue();
        assertThat(repository.release(current)).isTrue();
    }
}
