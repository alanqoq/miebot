package com.mieai.qqbot.client

import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.protocol.openapi.QqContentModels
import com.mieai.qqbot.protocol.openapi.QqGuildModels
import com.mieai.qqbot.protocol.openapi.QqMessageModels
import com.mieai.qqbot.protocol.openapi.QqPermissionModels
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

class QqOpenApiCoverageTest {
    @Test
    fun coversMessageLifecycleInteractionAndBotLinkEndpoints() {
        ApiServer().use { server ->
            val client = client(server)

            val directMessage = server.invoke(
                "POST",
                "/users/@me/dms",
                200,
                "{\"guild_id\":\"dm-1\",\"channel_id\":\"channel-1\"}",
            ) { client.createDirectMessage(QqMessageModels.DirectMessageRequest("guild-1", "user-1")) }
            assertThat(directMessage.body)
                .contains("\"source_guild_id\":\"guild-1\"")
                .contains("\"recipient_id\":\"user-1\"")

            val interaction = server.invoke("PUT", "/interactions/action-1", 204, "") {
                client.respondToInteraction("action-1", 0)
            }
            assertThat(interaction.body).isEqualTo("{\"code\":0}")
            assertThat(interaction.callbackAppId).isEqualTo(APP_ID.value)

            val link = server.invoke(
                "POST",
                "/v2/generate_url_link",
                200,
                "{\"url\":\"https://qun.qq.com/bot/add\"}",
            ) { client.generateUrlLink("campaign-a") }
            assertThat(link.body).isEqualTo("{\"callback_data\":\"campaign-a\"}")

            server.invoke("DELETE", "/v2/users/user-1/messages/message-1", 204, "") {
                client.recallMessage(QqMessageTargetType.C2C, "user-1", "message-1", false)
            }
            server.invoke("DELETE", "/v2/groups/group-1/messages/message-2?hidetip=true", 204, "") {
                client.recallMessage(QqMessageTargetType.GROUP, "group-1", "message-2", true)
            }
            server.invoke("DELETE", "/channels/channel-1/messages/message-3", 204, "") {
                client.recallMessage(QqMessageTargetType.CHANNEL, "channel-1", "message-3", false)
            }
            server.invoke("DELETE", "/dms/dm-1/messages/message-4", 204, "") {
                client.recallMessage(QqMessageTargetType.DIRECT, "dm-1", "message-4", false)
            }

            val options = QqMessageSendOptions(
                QqMessageModels.MessageReference("quoted-1", true),
                true,
            )
            val text = server.invoke("POST", "/v2/users/user-1/messages", 200, "{\"id\":\"sent-1\"}") {
                client.sendText(
                    QqTextMessageRequest(
                        QqMessageTargetType.C2C,
                        "user-1",
                        "hello",
                        null,
                        null,
                        1,
                    ),
                    options,
                )
            }
            assertThat(text.body)
                .contains("\"message_reference\"")
                .contains("\"message_id\":\"quoted-1\"")
                .contains("\"ignore_get_message_error\":true")
                .contains("\"is_wakeup\":true")
        }
    }

    @Test
    fun coversGuildChannelMemberAndRoleEndpoints() {
        ApiServer().use { server ->
            val client = client(server)

            server.invoke("GET", "/users/@me/guilds?after=cursor-1&limit=25", 200, "[]") {
                client.getCurrentGuilds(null, "cursor-1", 25)
            }
            server.invoke("GET", "/guilds/guild-1", 200, "{}") { client.getGuild("guild-1") }
            server.invoke("GET", "/guilds/guild-1/channels", 200, "[]") { client.getChannels("guild-1") }
            server.invoke("GET", "/channels/channel-1", 200, "{}") { client.getChannel("channel-1") }

            val channel = QqGuildModels.ChannelUpdate(
                "general",
                0,
                0,
                1L,
                "category-1",
                0,
                null,
                1,
                null,
            )
            val createChannel = server.invoke("POST", "/guilds/guild-1/channels", 200, "{}") {
                client.createChannel("guild-1", channel)
            }
            assertThat(createChannel.body)
                .contains("\"name\":\"general\"")
                .contains("\"parent_id\":\"category-1\"")
            server.invoke("PATCH", "/channels/channel-1", 200, "{}") {
                client.updateChannel("channel-1", channel)
            }
            server.invoke("DELETE", "/channels/channel-1", 204, "") { client.deleteChannel("channel-1") }

            server.invoke("GET", "/guilds/guild-1/members?after=user-0&limit=100", 200, "[]") {
                client.getGuildMembers("guild-1", "user-0", 100)
            }
            server.invoke("GET", "/guilds/guild-1/members/user-1", 200, "{}") {
                client.getGuildMember("guild-1", "user-1")
            }
            val remove = server.invoke("DELETE", "/guilds/guild-1/members/user-1", 204, "") {
                client.removeGuildMember("guild-1", "user-1", QqGuildModels.MemberDeleteRequest(true, 7))
            }
            assertThat(remove.body)
                .contains("\"add_blacklist\":true")
                .contains("\"delete_history_msg_days\":7")

            server.invoke(
                "GET",
                "/guilds/guild-1/roles/role-1/members?start_index=0&limit=20",
                200,
                "{\"data\":[],\"next\":\"0\"}",
            ) { client.getRoleMembers("guild-1", "role-1", "0", 20) }
            server.invoke("GET", "/guilds/guild-1/roles", 200, "{}") { client.getRoles("guild-1") }
            val createRole = server.invoke("POST", "/guilds/guild-1/roles", 200, "{}") {
                client.createRole("guild-1", QqGuildModels.RoleUpdate("moderator", 4_278_245_297L, 1))
            }
            assertThat(createRole.body)
                .contains("\"name\":\"moderator\"")
                .doesNotContain("\"filter\"")
                .doesNotContain("\"info\"")
            server.invoke("PATCH", "/guilds/guild-1/roles/role-1", 200, "{}") {
                client.updateRole("guild-1", "role-1", QqGuildModels.RoleUpdate("renamed", null, null))
            }
            server.invoke("DELETE", "/guilds/guild-1/roles/role-1", 204, "") {
                client.deleteRole("guild-1", "role-1")
            }
        }
    }

    @Test
    fun coversPermissionsRolesMuteAndGuildSettingsEndpoints() {
        ApiServer().use { server ->
            val client = client(server)

            server.invoke("PUT", "/guilds/guild-1/members/user-1/roles/role-1", 204, "") {
                client.addGuildMemberRole("guild-1", "user-1", "role-1", null)
            }
            server.invoke("DELETE", "/guilds/guild-1/members/user-1/roles/role-1", 204, "") {
                client.removeGuildMemberRole("guild-1", "user-1", "role-1", null)
            }
            server.invoke("GET", "/guilds/guild-1/api_permission", 200, "{\"apis\":[]}") {
                client.getGuildApiPermissions("guild-1")
            }
            server.invoke("POST", "/guilds/guild-1/api_permission/demand", 200, "{}") {
                client.demandGuildApiPermission(
                    "guild-1",
                    QqPermissionModels.ApiPermissionDemandRequest(
                        "channel-1",
                        QqPermissionModels.ApiIdentifier("/guilds/{guild_id}/members", "GET"),
                        "read members",
                    ),
                )
            }

            server.invoke("GET", "/channels/channel-1/members/user-1/permissions", 200, "{}") {
                client.getChannelMemberPermission("channel-1", "user-1")
            }
            server.invoke("PUT", "/channels/channel-1/members/user-1/permissions", 204, "") {
                client.updateChannelMemberPermission(
                    "channel-1",
                    "user-1",
                    QqPermissionModels.ChannelPermissionUpdate("1", "2"),
                )
            }
            server.invoke("GET", "/channels/channel-1/roles/role-1/permissions", 200, "{}") {
                client.getChannelRolePermission("channel-1", "role-1")
            }
            server.invoke("PUT", "/channels/channel-1/roles/role-1/permissions", 204, "") {
                client.updateChannelRolePermission(
                    "channel-1",
                    "role-1",
                    QqPermissionModels.ChannelPermissionUpdate("4", null),
                )
            }

            val mute = QqGuildModels.MuteRequest(null, "60", null)
            server.invoke("PATCH", "/guilds/guild-1/mute", 204, "") { client.muteGuild("guild-1", mute) }
            server.invoke("PATCH", "/guilds/guild-1/mute", 200, "{\"user_ids\":[\"user-1\"]}") {
                client.muteGuildMembers("guild-1", QqGuildModels.MuteRequest(null, "60", listOf("user-1")))
            }
            server.invoke("PATCH", "/guilds/guild-1/members/user-1/mute", 204, "") {
                client.muteGuildMember("guild-1", "user-1", mute)
            }
            server.invoke("GET", "/guilds/guild-1/message/setting", 200, "{}") {
                client.getGuildMessageSetting("guild-1")
            }
            server.invoke("GET", "/channels/channel-1/online_nums", 200, "{\"online_nums\":3}") {
                client.getChannelOnlineMemberCount("channel-1")
            }
        }
    }

    @Test
    fun coversReactionAnnouncementPinsScheduleForumAndAudioEndpoints() {
        ApiServer().use { server ->
            val client = client(server)

            server.invoke("PUT", "/channels/channel-1/messages/message-1/reactions/1/203", 204, "") {
                client.addMessageReaction("channel-1", "message-1", 1, "203")
            }
            server.invoke("DELETE", "/channels/channel-1/messages/message-1/reactions/1/203", 204, "") {
                client.deleteMessageReaction("channel-1", "message-1", 1, "203")
            }
            server.invoke(
                "GET",
                "/channels/channel-1/messages/message-1/reactions/1/203?cookie=next&limit=20",
                200,
                "{\"users\":[],\"is_end\":true}",
            ) { client.getMessageReactionUsers("channel-1", "message-1", 1, "203", "next", 20) }

            server.invoke("POST", "/guilds/guild-1/announces", 200, "{}") {
                client.createGuildAnnouncement(
                    "guild-1",
                    QqContentModels.GuildAnnouncementRequest("channel-1", "message-1", 0, emptyList()),
                )
            }
            server.invoke("DELETE", "/guilds/guild-1/announces/message-1", 204, "") {
                client.deleteGuildAnnouncement("guild-1", "message-1")
            }

            server.invoke("GET", "/channels/channel-1/pins", 200, "{}") { client.getPins("channel-1") }
            server.invoke("PUT", "/channels/channel-1/pins/message-1", 200, "{}") {
                client.addPin("channel-1", "message-1")
            }
            server.invoke("DELETE", "/channels/channel-1/pins/message-1", 204, "") {
                client.deletePin("channel-1", "message-1")
            }

            server.invoke("GET", "/channels/channel-1/schedules?since=123", 200, "[]") {
                client.getSchedules("channel-1", 123L)
            }
            server.invoke("GET", "/channels/channel-1/schedules/schedule-1", 200, "{}") {
                client.getSchedule("channel-1", "schedule-1")
            }
            val schedule = QqContentModels.ScheduleInput("meeting", null, "1000", "2000", "channel-1", "1")
            server.invoke("POST", "/channels/channel-1/schedules", 200, "{}") {
                client.createSchedule("channel-1", schedule)
            }
            server.invoke("PATCH", "/channels/channel-1/schedules/schedule-1", 200, "{}") {
                client.updateSchedule("channel-1", "schedule-1", schedule)
            }
            server.invoke("DELETE", "/channels/channel-1/schedules/schedule-1", 204, "") {
                client.deleteSchedule("channel-1", "schedule-1")
            }

            server.invoke("GET", "/channels/channel-1/threads", 200, "{\"threads\":[],\"is_finish\":1}") {
                client.getForumThreads("channel-1")
            }
            server.invoke("GET", "/channels/channel-1/threads/thread-1", 200, "{}") {
                client.getForumThread("channel-1", "thread-1")
            }
            server.invoke("PUT", "/channels/channel-1/threads", 200, "{\"task_id\":\"task-1\"}") {
                client.createForumThread("channel-1", QqContentModels.ForumThreadRequest("title", "content", 3))
            }
            server.invoke("DELETE", "/channels/channel-1/threads/thread-1", 204, "") {
                client.deleteForumThread("channel-1", "thread-1")
            }

            server.invoke("POST", "/channels/channel-1/audio", 204, "") {
                client.controlAudio(
                    "channel-1",
                    QqContentModels.AudioControl("https://cdn.example/audio.mp3", "track", 0),
                )
            }
            server.invoke("PUT", "/channels/channel-1/mic", 204, "") { client.putBotOnMic("channel-1") }
            server.invoke("DELETE", "/channels/channel-1/mic", 204, "") { client.removeBotFromMic("channel-1") }
        }
    }

    private fun client(server: ApiServer): QqOpenApiClient {
        val options = QqClientOptions(
            tokenEndpoint = server.server.uri("/token"),
            openApiBaseUri = server.server.uri("/"),
            requestTimeout = Duration.ofSeconds(2),
        )
        val tokenProvider = TokenProvider {
            CompletableFuture.completedFuture(
                AccessToken.of(ACCESS_TOKEN, Instant.parse("2026-07-22T14:00:00Z")),
            )
        }
        return QqOpenApiClient(options, tokenProvider, APP_ID)
    }

    private data class Captured(
        val method: String,
        val path: String,
        val body: String,
        val authorization: String?,
        val unionAppId: String?,
        val callbackAppId: String?,
    )

    private class ApiServer : AutoCloseable {
        val server = TestHttpServer()
        private val nextResponse = AtomicReference<Response?>()
        private val captured = AtomicReference<Captured?>()

        init {
            server.handle("/") { exchange ->
                captured.set(
                    Captured(
                        exchange.requestMethod,
                        exchange.requestURI.toString(),
                        TestHttpServer.readBody(exchange),
                        exchange.requestHeaders.getFirst("Authorization"),
                        exchange.requestHeaders.getFirst("X-Union-Appid"),
                        exchange.requestHeaders.getFirst("X-Callback-AppID"),
                    ),
                )
                val response = requireNotNull(nextResponse.get())
                if (response.status == 204) {
                    exchange.sendResponseHeaders(204, -1L)
                    exchange.close()
                } else {
                    TestHttpServer.respond(exchange, response.status, response.body)
                }
            }
        }

        fun <T> invoke(
            method: String,
            path: String,
            status: Int,
            responseBody: String,
            invocation: () -> CompletionStage<T>,
        ): Captured {
            captured.set(null)
            nextResponse.set(Response(status, responseBody))
            invocation().toCompletableFuture().join()
            val request = requireNotNull(captured.get())
            assertThat(request.method).isEqualTo(method)
            assertThat(request.path).isEqualTo(path)
            assertThat(request.authorization).isEqualTo("QQBot $ACCESS_TOKEN")
            assertThat(request.unionAppId).isEqualTo(APP_ID.value)
            return request
        }

        override fun close() = server.close()
    }

    private data class Response(val status: Int, val body: String)

    companion object {
        private const val ACCESS_TOKEN = "coverage-token"
        private val APP_ID = QqAppId.of("app-123")
    }
}
