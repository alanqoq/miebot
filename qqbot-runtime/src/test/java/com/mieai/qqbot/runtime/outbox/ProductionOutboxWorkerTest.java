package com.mieai.qqbot.runtime.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.client.QqClientOptions;
import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.client.QqMessageTargetType;
import com.mieai.qqbot.domain.bot.BotEnvironment;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository;
import com.mieai.qqbot.persistence.outbox.NewOutboxJob;
import com.mieai.qqbot.persistence.outbox.OutboxStatus;
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory;
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer;
import com.mieai.qqbot.runtime.security.AppSecret;
import com.mieai.qqbot.runtime.security.AppSecretBinding;
import com.mieai.qqbot.runtime.security.AppSecretCipher;
import com.mieai.qqbot.persistence.bot.SecretCiphertext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProductionOutboxWorkerTest {
    private static final String BOT = "550e8400-e29b-41d4-a716-446655440001";
    private static final Instant BASE_TIME = Instant.parse("2026-07-19T00:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void claimsSendsAndCompletesTextOutboxJob() throws Exception {
        try (HttpFixture http = new HttpFixture()) {
            var dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("outbox-worker.db"));
            SQLiteDatabaseInitializer.migrate(dataSource);
            insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX);
            var repository = new JdbcOutboxRepository(dataSource);
            ObjectMapper mapper = new ObjectMapper();
            String payload = mapper.writeValueAsString(new OutboundTextPayload(
                    QqMessageTargetType.GROUP, "group-1", "hello", null, null, 1));
            UUID jobId = UUID.randomUUID();
            repository.create(new NewOutboxJob(jobId, BotEnvironment.SANDBOX, BotId.parse(BOT), Optional.empty(),
                    OutboundTextPayload.JOB_TYPE, Optional.of("test-job"), payload, BASE_TIME, BASE_TIME));

            var scheduler = Executors.newSingleThreadScheduledExecutor();
            AppSecretCipher cipher = new AppSecretCipher() {
                @Override public SecretCiphertext encrypt(AppSecret secret, AppSecretBinding binding) { return SecretCiphertext.of("cipher", "key"); }
                @Override public AppSecret decrypt(SecretCiphertext ciphertext, AppSecretBinding binding) { return AppSecret.of("secret"); }
            };
            QqClientOptions options = QqClientOptions.builder()
                    .tokenEndpoint(http.uri("/token"))
                    .openApiBaseUri(http.uri("/"))
                    .requestTimeout(Duration.ofSeconds(1))
                    .tokenRefreshSkew(Duration.ZERO)
                    .build();
            ProductionOutboxWorker worker = new ProductionOutboxWorker(repository,
                    new com.mieai.qqbot.persistence.bot.JdbcBotRepository(dataSource), cipher,
                    ignored -> options, mapper, scheduler, java.time.Clock.fixed(BASE_TIME.plusSeconds(1), java.time.ZoneOffset.UTC), Duration.ofMillis(10),
                    Duration.ofSeconds(5), Duration.ofSeconds(1), 3, 2);
            try {
                worker.start();
                Instant deadline = Instant.now().plusSeconds(5);
                OutboxStatus status;
                do {
                    Thread.sleep(30);
                    status = repository.findById(jobId).orElseThrow().status();
                } while ((status == OutboxStatus.PENDING || status == OutboxStatus.IN_PROGRESS
                        || status == OutboxStatus.RETRY_WAIT) && Instant.now().isBefore(deadline));
                var stored = repository.findById(jobId).orElseThrow();
                assertThat(status).withFailMessage("worker did not complete: %s", stored.lastError())
                        .isEqualTo(OutboxStatus.SUCCEEDED);
                assertThat(http.authorization.get()).isEqualTo("QQBot token-value");
                assertThat(http.path.get()).isEqualTo("/v2/groups/group-1/messages");
                assertThat(http.body.get()).contains("hello").contains("\"msg_seq\":1");
            } finally {
                worker.close();
                scheduler.shutdownNow();
            }
        }
    }

    @Test
    void uploadsAndCompletesMediaOutboxJob() throws Exception {
        try (HttpFixture http = new HttpFixture()) {
            var dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("media-worker.db"));
            SQLiteDatabaseInitializer.migrate(dataSource);
            insertBot(dataSource, BOT, "10002", BotEnvironment.SANDBOX);
            var repository = new JdbcOutboxRepository(dataSource);
            ObjectMapper mapper = new ObjectMapper();
            String payload = mapper.writeValueAsString(new OutboundMediaPayload(
                    QqMessageTargetType.GROUP, "group-1",
                    QqMediaKind.IMAGE, "https://cdn.example/image.png",
                    "caption", null, null, 2));
            UUID jobId = UUID.randomUUID();
            repository.create(new NewOutboxJob(jobId, BotEnvironment.SANDBOX, BotId.parse(BOT), Optional.empty(),
                    OutboundMediaPayload.JOB_TYPE, Optional.of("media-job"), payload, BASE_TIME, BASE_TIME));
            var scheduler = Executors.newSingleThreadScheduledExecutor();
            AppSecretCipher cipher = new AppSecretCipher() {
                @Override public SecretCiphertext encrypt(AppSecret secret, AppSecretBinding binding) { return SecretCiphertext.of("cipher", "key"); }
                @Override public AppSecret decrypt(SecretCiphertext ciphertext, AppSecretBinding binding) { return AppSecret.of("secret"); }
            };
            QqClientOptions options = QqClientOptions.builder().tokenEndpoint(http.uri("/token"))
                    .openApiBaseUri(http.uri("/")).requestTimeout(Duration.ofSeconds(1))
                    .tokenRefreshSkew(Duration.ZERO).build();
            ProductionOutboxWorker worker = new ProductionOutboxWorker(repository,
                    new com.mieai.qqbot.persistence.bot.JdbcBotRepository(dataSource), cipher,
                    ignored -> options, mapper, scheduler,
                    java.time.Clock.fixed(BASE_TIME.plusSeconds(1), java.time.ZoneOffset.UTC),
                    Duration.ofMillis(10), Duration.ofSeconds(5), Duration.ofSeconds(1), 3, 2);
            try {
                worker.start();
                Instant deadline = Instant.now().plusSeconds(5);
                OutboxStatus status;
                do {
                    Thread.sleep(30);
                    status = repository.findById(jobId).orElseThrow().status();
                } while ((status == OutboxStatus.PENDING || status == OutboxStatus.IN_PROGRESS
                        || status == OutboxStatus.RETRY_WAIT) && Instant.now().isBefore(deadline));
                assertThat(status).isEqualTo(OutboxStatus.SUCCEEDED);
                assertThat(http.uploadBody.get()).contains("\"file_type\":1").contains("image.png");
                assertThat(http.body.get()).contains("signed-file-info").contains("\"msg_type\":7");
            } finally {
                worker.close();
                scheduler.shutdownNow();
            }
        }
    }

    private static void insertBot(javax.sql.DataSource dataSource, String id, String appId,
            BotEnvironment environment) throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement("""
                        INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext,
                            app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at)
                        VALUES (?, 'Worker Bot', ?, ?, 'ciphertext', 'key', 33554432, 0, 1, 1, 1, ?, ?)
                        """)) {
            statement.setString(1, id);
            statement.setString(2, appId);
            statement.setString(3, environment.name());
            statement.setString(4, BASE_TIME.toString());
            statement.setString(5, BASE_TIME.toString());
            statement.executeUpdate();
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();
        private final AtomicReference<String> uploadBody = new AtomicReference<>();

        private HttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/token", exchange -> respond(exchange, 200,
                    "{\"access_token\":\"token-value\",\"expires_in\":3600}"));
            server.createContext("/v2/groups/group-1/messages", exchange -> {
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                path.set(exchange.getRequestURI().getPath());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"id\":\"sent-1\",\"msg_seq\":1}");
            });
            server.createContext("/v2/groups/group-1/files", exchange -> {
                uploadBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, 200, "{\"file_info\":\"signed-file-info\",\"ttl\":60}");
            });
            server.start();
        }

        private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
            byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, encoded.length);
            try (exchange; var output = exchange.getResponseBody()) { output.write(encoded); }
        }

        @Override public void close() { server.stop(0); }
    }
}
