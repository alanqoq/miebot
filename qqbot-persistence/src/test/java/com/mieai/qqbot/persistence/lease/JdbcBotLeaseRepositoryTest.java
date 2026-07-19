package com.mieai.qqbot.persistence.lease;

import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.BASE_TIME;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.insertBot;
import static com.mieai.qqbot.persistence.test.PersistenceTestFixture.migratedDatabase;
import static org.assertj.core.api.Assertions.assertThat;

import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
