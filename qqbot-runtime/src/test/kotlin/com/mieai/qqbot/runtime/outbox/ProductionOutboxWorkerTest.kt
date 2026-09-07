package com.mieai.qqbot.runtime.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.client.FileMediaAssetStore
import com.mieai.qqbot.client.MediaAssetStore
import com.mieai.qqbot.client.QqClientOptions
import com.mieai.qqbot.client.QqMediaKind
import com.mieai.qqbot.client.QqMessageTargetType
import com.mieai.qqbot.client.QqRichMessageKind
import com.mieai.qqbot.domain.bot.BotEnvironment
import com.mieai.qqbot.domain.bot.BotId
import com.mieai.qqbot.persistence.bot.JdbcBotRepository
import com.mieai.qqbot.persistence.bot.SecretCiphertext
import com.mieai.qqbot.persistence.outbox.JdbcOutboxRepository
import com.mieai.qqbot.persistence.outbox.NewOutboxJob
import com.mieai.qqbot.persistence.outbox.OutboxStatus
import com.mieai.qqbot.persistence.sqlite.SQLiteDataSourceFactory
import com.mieai.qqbot.persistence.sqlite.SQLiteDatabaseInitializer
import com.mieai.qqbot.runtime.security.AppSecret
import com.mieai.qqbot.runtime.security.AppSecretBinding
import com.mieai.qqbot.runtime.security.AppSecretCipher
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductionOutboxWorkerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `claims sends and completes text Outbox job`() {
        HttpFixture().use { http ->
            val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("outbox-worker.db"))
            SQLiteDatabaseInitializer.migrate(dataSource)
            insertBot(dataSource, BOT, "10001", BotEnvironment.SANDBOX)
            val repository = JdbcOutboxRepository(dataSource)
            val mapper = ObjectMapper()
            val payload = mapper.writeValueAsString(
                OutboundTextPayload(
                    QqMessageTargetType.GROUP,
                    "group-1",
                    "hello",
                    null,
                    null,
                    1,
                    OutboundMessageReference("quoted-message-1", true),
                ),
            )
            val jobId = UUID.randomUUID()
            repository.create(
                NewOutboxJob(
                    jobId,
                    BotEnvironment.SANDBOX,
                    BotId.parse(BOT),
                    null,
                    OutboundTextPayload.JOB_TYPE,
                    "test-job",
                    payload,
                    BASE_TIME,
                    BASE_TIME,
                    null,
                ),
            )

            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val worker = worker(repository, dataSource, mapper, scheduler, http)
            try {
                worker.start()
                val deadline = Instant.now().plusSeconds(5)
                var status: OutboxStatus
                do {
                    Thread.sleep(30)
                    status = requireNotNull(repository.findById(jobId)).status
                } while (
                    (status == OutboxStatus.PENDING ||
                        status == OutboxStatus.IN_PROGRESS ||
                        status == OutboxStatus.RETRY_WAIT) &&
                    Instant.now().isBefore(deadline)
                )
                val stored = requireNotNull(repository.findById(jobId))
                assertThat(status).withFailMessage("worker did not complete: %s", stored.lastError)
                    .isEqualTo(OutboxStatus.SUCCEEDED)
                assertThat(stored.platformMessageId).isEqualTo("sent-1")
                assertThat(stored.platformMessageSequence).isEqualTo(1)
                assertThat(http.authorization.get()).isEqualTo("QQBot token-value")
                assertThat(http.path.get()).isEqualTo("/v2/groups/group-1/messages")
                assertThat(http.body.get())
                    .contains("hello")
                    .contains("\"msg_seq\":1")
                    .contains("\"message_reference\"")
                    .contains("\"message_id\":\"quoted-message-1\"")
                    .contains("\"ignore_get_message_error\":true")
            } finally {
                worker.close()
                scheduler.shutdownNow()
            }
        }
    }

    @Test
    fun `records result unknown when successful response omits message ID`() {
        HttpFixture("{}").use { http ->
            val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("missing-message-id.db"))
            SQLiteDatabaseInitializer.migrate(dataSource)
            insertBot(dataSource, BOT, "10003", BotEnvironment.SANDBOX)
            val repository = JdbcOutboxRepository(dataSource)
            val mapper = ObjectMapper()
            val payload =
                """{"targetType":"GROUP","targetId":"group-1","content":"hello","replyMessageId":null,"replyEventId":null,"messageSequence":1}"""
            val jobId = UUID.randomUUID()
            repository.create(
                NewOutboxJob(
                    jobId,
                    BotEnvironment.SANDBOX,
                    BotId.parse(BOT),
                    null,
                    OutboundTextPayload.JOB_TYPE,
                    "missing-message-id",
                    payload,
                    BASE_TIME,
                    BASE_TIME,
                    null,
                ),
            )

            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val worker = worker(repository, dataSource, mapper, scheduler, http)
            try {
                worker.start()
                val deadline = Instant.now().plusSeconds(5)
                var status: OutboxStatus
                do {
                    Thread.sleep(30)
                    status = requireNotNull(repository.findById(jobId)).status
                } while (
                    (status == OutboxStatus.PENDING ||
                        status == OutboxStatus.IN_PROGRESS ||
                        status == OutboxStatus.RETRY_WAIT) &&
                    Instant.now().isBefore(deadline)
                )

                val stored = requireNotNull(repository.findById(jobId))
                assertThat(status).isEqualTo(OutboxStatus.RESULT_UNKNOWN)
                assertThat(stored.platformMessageId).isNull()
                assertThat(requireNotNull(stored.lastError)).contains("no message ID")
            } finally {
                worker.close()
                scheduler.shutdownNow()
            }
        }
    }

    @Test
    fun `uploads and completes media Outbox job`() {
        HttpFixture("{\"id\":\"media-sent\",\"msg_seq\":0,\"timestamp\":\" invalid \"}").use { http ->
            val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("media-worker.db"))
            SQLiteDatabaseInitializer.migrate(dataSource)
            insertBot(dataSource, BOT, "10002", BotEnvironment.SANDBOX)
            val repository = JdbcOutboxRepository(dataSource)
            val mapper = ObjectMapper()
            val payload = mapper.writeValueAsString(
                OutboundMediaPayload(
                    QqMessageTargetType.GROUP,
                    "group-1",
                    QqMediaKind.IMAGE,
                    "https://cdn.example/image.png",
                    "caption",
                    null,
                    null,
                    2,
                    messageReference = OutboundMessageReference("quoted-media-1", false),
                ),
            )
            val jobId = UUID.randomUUID()
            repository.create(
                NewOutboxJob(
                    jobId,
                    BotEnvironment.SANDBOX,
                    BotId.parse(BOT),
                    null,
                    OutboundMediaPayload.JOB_TYPE,
                    "media-job",
                    payload,
                    BASE_TIME,
                    BASE_TIME,
                    null,
                ),
            )

            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val worker = worker(repository, dataSource, mapper, scheduler, http)
            try {
                worker.start()
                val deadline = Instant.now().plusSeconds(5)
                var status: OutboxStatus
                do {
                    Thread.sleep(30)
                    status = requireNotNull(repository.findById(jobId)).status
                } while (
                    (status == OutboxStatus.PENDING ||
                        status == OutboxStatus.IN_PROGRESS ||
                        status == OutboxStatus.RETRY_WAIT) &&
                    Instant.now().isBefore(deadline)
                )
                val stored = requireNotNull(repository.findById(jobId))
                assertThat(status).isEqualTo(OutboxStatus.SUCCEEDED)
                assertThat(stored.platformMessageId).isEqualTo("media-sent")
                assertThat(stored.platformMessageSequence).isNull()
                assertThat(stored.platformTimestamp).isNull()
                assertThat(http.uploadBody.get()).contains("\"file_type\":1").contains("image.png")
                assertThat(http.body.get())
                    .contains("signed-file-info")
                    .contains("\"msg_type\":7")
                    .contains("\"message_id\":\"quoted-media-1\"")
                    .contains("\"ignore_get_message_error\":false")
            } finally {
                worker.close()
                scheduler.shutdownNow()
            }
        }
    }

    @Test
    fun `renews lease while uploading staged media and deletes it after success`() {
        HttpFixture(blockPartUpload = true).use { http ->
            val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("staged-media-worker.db"))
            SQLiteDatabaseInitializer.migrate(dataSource)
            insertBot(dataSource, BOT, "10005", BotEnvironment.SANDBOX)
            val repository = JdbcOutboxRepository(dataSource)
            val mapper = ObjectMapper()
            val store = FileMediaAssetStore(temporaryDirectory.resolve("media"))
            val asset = store.stage(
                BotId.parse(BOT),
                QqMediaKind.IMAGE,
                "photo.png",
                "image/png",
                ByteArrayInputStream("abc".toByteArray()),
                1024,
            )
            val jobId = UUID.randomUUID()
            repository.create(
                NewOutboxJob(
                    jobId,
                    BotEnvironment.SANDBOX,
                    BotId.parse(BOT),
                    null,
                    OutboundMediaPayload.JOB_TYPE,
                    "staged-media-job",
                    mapper.writeValueAsString(
                        OutboundMediaPayload(
                            QqMessageTargetType.GROUP,
                            "group-1",
                            QqMediaKind.IMAGE,
                            "https://cdn.example/image.png",
                            null,
                            null,
                            null,
                            1,
                            asset.id,
                        ),
                    ),
                    BASE_TIME,
                    BASE_TIME,
                    null,
                ),
            )
            val clock = MutableClock(BASE_TIME.plusSeconds(1))
            val scheduler = Executors.newScheduledThreadPool(2)
            val worker = worker(
                repository,
                dataSource,
                mapper,
                scheduler,
                http,
                clock,
                Duration.ofMillis(600),
                Duration.ofMillis(100),
                store,
            )
            try {
                worker.start()
                assertThat(http.partStarted.await(3, TimeUnit.SECONDS)).isTrue()
                val firstLease = requireNotNull(repository.findById(jobId)).leaseUntil
                clock.advance(Duration.ofMillis(300))
                Thread.sleep(300)
                assertThat(requireNotNull(repository.findById(jobId)).leaseUntil).isAfter(firstLease)
                http.releasePart.countDown()
                awaitTerminal(repository, jobId)
                assertThat(requireNotNull(repository.findById(jobId)).status).isEqualTo(OutboxStatus.SUCCEEDED)
                assertThat(store.find(BotId.parse(BOT), asset.id)).isNull()
            } finally {
                http.releasePart.countDown()
                worker.close()
                scheduler.shutdownNow()
            }
        }
    }

    @Test
    fun `sends explicit references with rich messages`() {
        HttpFixture().use { http ->
            val dataSource = SQLiteDataSourceFactory.create(temporaryDirectory.resolve("rich-reference-worker.db"))
            SQLiteDatabaseInitializer.migrate(dataSource)
            insertBot(dataSource, BOT, "10004", BotEnvironment.SANDBOX)
            val repository = JdbcOutboxRepository(dataSource)
            val mapper = ObjectMapper()
            val payload = mapper.writeValueAsString(
                OutboundRichPayload(
                    QqMessageTargetType.GROUP,
                    "group-1",
                    QqRichMessageKind.MARKDOWN,
                    mapOf("content" to "**hello**"),
                    null,
                    null,
                    1,
                    OutboundMessageReference("quoted-rich-1", false),
                ),
            )
            val jobId = UUID.randomUUID()
            repository.create(
                NewOutboxJob(
                    jobId,
                    BotEnvironment.SANDBOX,
                    BotId.parse(BOT),
                    null,
                    OutboundRichPayload.JOB_TYPE,
                    "rich-reference-job",
                    payload,
                    BASE_TIME,
                    BASE_TIME,
                    null,
                ),
            )

            val scheduler = Executors.newSingleThreadScheduledExecutor()
            val worker = worker(repository, dataSource, mapper, scheduler, http)
            try {
                worker.start()
                val deadline = Instant.now().plusSeconds(5)
                var status: OutboxStatus
                do {
                    Thread.sleep(30)
                    status = requireNotNull(repository.findById(jobId)).status
                } while (
                    (status == OutboxStatus.PENDING ||
                        status == OutboxStatus.IN_PROGRESS ||
                        status == OutboxStatus.RETRY_WAIT) &&
                    Instant.now().isBefore(deadline)
                )

                val stored = requireNotNull(repository.findById(jobId))
                assertThat(status).withFailMessage("worker did not complete: %s", stored.lastError)
                    .isEqualTo(OutboxStatus.SUCCEEDED)
                assertThat(http.body.get())
                    .contains("\"markdown\"")
                    .contains("\"message_id\":\"quoted-rich-1\"")
                    .contains("\"ignore_get_message_error\":false")
            } finally {
                worker.close()
                scheduler.shutdownNow()
            }
        }
    }

    private fun worker(
        repository: JdbcOutboxRepository,
        dataSource: DataSource,
        mapper: ObjectMapper,
        scheduler: java.util.concurrent.ScheduledExecutorService,
        http: HttpFixture,
        clock: Clock = Clock.fixed(BASE_TIME.plusSeconds(1), ZoneOffset.UTC),
        leaseDuration: Duration = Duration.ofSeconds(5),
        requestWait: Duration = Duration.ofSeconds(1),
        mediaStore: MediaAssetStore? = null,
    ): ProductionOutboxWorker {
        val cipher = object : AppSecretCipher {
            override fun encrypt(secret: AppSecret, binding: AppSecretBinding) = SecretCiphertext.of("cipher", "key")

            override fun decrypt(ciphertext: SecretCiphertext, binding: AppSecretBinding) = AppSecret.of("secret")
        }
        val options = QqClientOptions(
            tokenEndpoint = http.uri("/token"),
            openApiBaseUri = http.uri("/"),
            requestTimeout = Duration.ofSeconds(1),
            tokenRefreshSkew = Duration.ZERO,
        )
        return ProductionOutboxWorker(
            repository,
            JdbcBotRepository(dataSource),
            cipher,
            { options },
            mapper,
            scheduler,
            clock,
            Duration.ofMillis(10),
            leaseDuration,
            requestWait,
            3,
            2,
            mediaStore = mediaStore,
        )
    }

    private fun awaitTerminal(repository: JdbcOutboxRepository, jobId: UUID) {
        val deadline = Instant.now().plusSeconds(5)
        while (Instant.now().isBefore(deadline)) {
            if (requireNotNull(repository.findById(jobId)).status.isTerminal()) return
            Thread.sleep(20)
        }
    }

    private fun insertBot(dataSource: DataSource, id: String, appId: String, environment: BotEnvironment) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO bots (id, display_name, app_id, environment, app_secret_ciphertext,
                    app_secret_key_id, intents, shard_index, shard_count, enabled, revision, created_at, updated_at)
                VALUES (?, 'Worker Bot', ?, ?, 'ciphertext', 'key', 33554432, 0, 1, 1, 1, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, appId)
                statement.setString(3, environment.name)
                statement.setString(4, BASE_TIME.toString())
                statement.setString(5, BASE_TIME.toString())
                statement.executeUpdate()
            }
        }
    }

    private class HttpFixture(
        private val messageResponse: String = "{\"id\":\"sent-1\",\"msg_seq\":1}",
        private val blockPartUpload: Boolean = false,
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val authorization = AtomicReference<String?>()
        val path = AtomicReference<String?>()
        val body = AtomicReference<String?>()
        val uploadBody = AtomicReference<String?>()
        val partStarted = CountDownLatch(1)
        val releasePart = CountDownLatch(1)

        init {
            server.createContext("/token") { exchange ->
                respond(exchange, 200, "{\"access_token\":\"token-value\",\"expires_in\":3600}")
            }
            server.createContext("/v2/groups/group-1/messages") { exchange ->
                authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                path.set(exchange.requestURI.path)
                body.set(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
                respond(exchange, 200, messageResponse)
            }
            server.createContext("/v2/groups/group-1/files") { exchange ->
                uploadBody.set(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
                respond(exchange, 200, "{\"file_info\":\"signed-file-info\",\"ttl\":60}")
            }
            server.createContext("/v2/groups/group-1/upload_prepare") { exchange ->
                uploadBody.set(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8))
                respond(
                    exchange,
                    200,
                    "{\"upload_id\":\"staged-upload\",\"block_size\":\"3\",\"parts\":[" +
                        "{\"index\":0,\"presigned_url\":\"${uri("/part")}\",\"block_size\":\"3\"}]," +
                        "\"upload_config\":{\"concurrency\":1,\"retry_timeout\":1,\"retry_delay\":0}}",
                )
            }
            server.createContext("/part") { exchange ->
                partStarted.countDown()
                if (blockPartUpload) releasePart.await(3, TimeUnit.SECONDS)
                exchange.requestBody.readAllBytes()
                respond(exchange, 200, "")
            }
            server.createContext("/v2/groups/group-1/upload_part_finish") { exchange ->
                exchange.requestBody.readAllBytes()
                respond(exchange, 200, "")
            }
            server.start()
        }

        fun uri(path: String): URI = URI.create("http://127.0.0.1:${server.address.port}$path")

        override fun close() {
            server.stop(0)
        }

        private fun respond(exchange: HttpExchange, status: Int, body: String) {
            val encoded = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, encoded.size.toLong())
            try {
                exchange.responseBody.use { output -> output.write(encoded) }
            } finally {
                exchange.close()
            }
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        private val now = AtomicReference(initial)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = now.get()
        fun advance(duration: Duration) { now.updateAndGet { it.plus(duration) } }
    }

    private companion object {
        const val BOT = "550e8400-e29b-41d4-a716-446655440001"
        val BASE_TIME: Instant = Instant.parse("2026-07-19T00:00:00Z")
    }
}
