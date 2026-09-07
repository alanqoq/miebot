package com.mieai.qqbot.client

import com.fasterxml.jackson.annotation.JsonProperty
import com.mieai.qqbot.domain.bot.BotProfile
import com.mieai.qqbot.domain.bot.QqAppId
import com.mieai.qqbot.protocol.gateway.GatewayUrlResponse
import com.mieai.qqbot.protocol.json.JsonCodec
import com.mieai.qqbot.protocol.json.JsonCodecs
import com.mieai.qqbot.protocol.openapi.QqContentModels
import com.mieai.qqbot.protocol.openapi.QqGuildModels
import com.mieai.qqbot.protocol.openapi.QqMessageModels
import com.mieai.qqbot.protocol.openapi.QqPermissionModels
import com.mieai.qqbot.protocol.user.CurrentBotUser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.HashSet
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.StringJoiner
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Asynchronous client for current, bot-scoped QQ OpenAPI operations. */
class QqOpenApiClient(
    private val options: QqClientOptions,
    private val tokenProvider: TokenProvider,
    applicationId: QqAppId? = null,
) {
    private val jsonCodec: JsonCodec = JsonCodecs.defaultCodec()
    private val transport = JdkQqHttpTransport(jsonCodec, options.requestTimeout)
    private val mediaDownloader = QqMediaDownloader(
        options.maxMediaBytes,
        options.mediaDownloadTimeout,
        options.maxMediaRedirects,
    )
    private val appId: String? = applicationId?.value
    private val applicationHeaders: Map<String, String> =
        if (applicationId == null) emptyMap() else mapOf("X-Union-Appid" to applicationId.value)

    fun getGateway(): CompletionStage<URI> {
        val endpoint = endpoint(GATEWAY_PATH)
        return authorizedGet(endpoint, GatewayUrlResponse::class.java)
            .thenApply { parseGatewayUri(it.url, endpoint) }
    }

    fun getGatewayBot(): CompletionStage<GatewayBotInfo> {
        val endpoint = endpoint(GATEWAY_BOT_PATH)
        return authorizedGet(endpoint, GatewayBotProtocolResponse::class.java)
            .thenApply { toGatewayBotInfo(it, endpoint) }
    }

    fun getCurrentBot(): CompletionStage<BotProfile> {
        val endpoint = endpoint(CURRENT_BOT_PATH)
        return authorizedGet(endpoint, CurrentBotUser::class.java)
            .thenApply { toBotProfile(it, endpoint) }
    }

    fun recallMessage(
        targetType: QqMessageTargetType,
        targetId: String,
        messageId: String,
        hideTip: Boolean,
    ): CompletionStage<Void> {
        var targetEndpoint = messageResourceEndpoint(targetType, targetId, messageId)
        if (hideTip) {
            targetEndpoint = withQuery(targetEndpoint, mapOf("hidetip" to true))
        }
        return authorizedDelete(targetEndpoint, Void::class.java)
    }

    fun createDirectMessage(
        request: QqMessageModels.DirectMessageRequest,
    ): CompletionStage<QqMessageModels.DirectMessage> {
        return authorizedPost(endpoint("users/@me/dms"), request, QqMessageModels.DirectMessage::class.java)
    }

    fun respondToInteraction(interactionId: String, code: Int): CompletionStage<Void> {
        require(code in 0..5) { "code must be between 0 and 5" }
        val callbackAppId = appId ?: return CompletableFuture.failedFuture(
            IllegalStateException("An AppID is required to respond to interactions"),
        )
        val headers = HashMap(applicationHeaders)
        headers["X-Callback-AppID"] = callbackAppId
        return authorizedPut(
            endpoint("interactions/${pathSegment(interactionId, "interactionId")}"),
            QqMessageModels.InteractionResponse(code),
            headers.toMap(),
            Void::class.java,
        )
    }

    fun generateUrlLink(callbackData: String?): CompletionStage<QqMessageModels.UrlLink> {
        require(callbackData == null || callbackData.codePointCount(0, callbackData.length) <= 32) {
            "callbackData must not exceed 32 characters"
        }
        return authorizedPost(
            endpoint("v2/generate_url_link"),
            QqMessageModels.UrlLinkRequest(normalizeOptional(callbackData)),
            QqMessageModels.UrlLink::class.java,
        )
    }

    fun getCurrentGuilds(): CompletionStage<List<QqGuildModels.Guild>> =
        getCurrentGuilds(null, null, null)

    fun getCurrentGuilds(
        before: String?,
        after: String?,
        limit: Int?,
    ): CompletionStage<List<QqGuildModels.Guild>> {
        require(before == null || after == null) { "before and after cannot both be set" }
        validateOptionalLimit(limit, 1, 100, "limit")
        val query = LinkedHashMap<String, Any>()
        putOptional(query, "before", before)
        putOptional(query, "after", after)
        putOptional(query, "limit", limit)
        return authorizedGet(
            withQuery(endpoint("users/@me/guilds"), query),
            Array<QqGuildModels.Guild>::class.java,
        ).thenApply { immutableList(it) }
    }

    fun getGuild(guildId: String): CompletionStage<QqGuildModels.Guild> =
        authorizedGet(endpoint("guilds/${pathSegment(guildId, "guildId")}"), QqGuildModels.Guild::class.java)

    fun getChannels(guildId: String): CompletionStage<List<QqGuildModels.Channel>> =
        authorizedGet(
            endpoint("guilds/${pathSegment(guildId, "guildId")}/channels"),
            Array<QqGuildModels.Channel>::class.java,
        ).thenApply { immutableList(it) }

    fun getChannel(channelId: String): CompletionStage<QqGuildModels.Channel> =
        authorizedGet(
            endpoint("channels/${pathSegment(channelId, "channelId")}"),
            QqGuildModels.Channel::class.java,
        )

    fun createChannel(
        guildId: String,
        request: QqGuildModels.ChannelUpdate,
    ): CompletionStage<QqGuildModels.Channel> {
        return authorizedPost(
            endpoint("guilds/${pathSegment(guildId, "guildId")}/channels"),
            request,
            QqGuildModels.Channel::class.java,
        )
    }

    fun updateChannel(
        channelId: String,
        request: QqGuildModels.ChannelUpdate,
    ): CompletionStage<QqGuildModels.Channel> {
        return authorizedPatch(
            endpoint("channels/${pathSegment(channelId, "channelId")}"),
            request,
            QqGuildModels.Channel::class.java,
        )
    }

    fun deleteChannel(channelId: String): CompletionStage<Void> =
        authorizedDelete(endpoint("channels/${pathSegment(channelId, "channelId")}"), Void::class.java)

    fun getGuildMembers(
        guildId: String,
        after: String?,
        limit: Int?,
    ): CompletionStage<List<QqGuildModels.Member>> {
        validateOptionalLimit(limit, 1, 1000, "limit")
        val query = LinkedHashMap<String, Any>()
        putOptional(query, "after", after)
        putOptional(query, "limit", limit)
        val targetEndpoint = endpoint("guilds/${pathSegment(guildId, "guildId")}/members")
        return authorizedGet(withQuery(targetEndpoint, query), Array<QqGuildModels.Member>::class.java)
            .thenApply { immutableList(it) }
    }

    fun getGuildMember(guildId: String, userId: String): CompletionStage<QqGuildModels.Member> =
        authorizedGet(guildMemberEndpoint(guildId, userId), QqGuildModels.Member::class.java)

    fun removeGuildMember(
        guildId: String,
        userId: String,
        request: QqGuildModels.MemberDeleteRequest,
    ): CompletionStage<Void> {
        return authorizedDelete(guildMemberEndpoint(guildId, userId), request, Void::class.java)
    }

    fun getRoleMembers(
        guildId: String,
        roleId: String,
        startIndex: String?,
        limit: Int?,
    ): CompletionStage<QqGuildModels.RoleMembersPage> {
        validateOptionalLimit(limit, 1, 400, "limit")
        val query = LinkedHashMap<String, Any>()
        putOptional(query, "start_index", startIndex)
        putOptional(query, "limit", limit)
        val targetEndpoint = endpoint(
            "guilds/${pathSegment(guildId, "guildId")}/roles/" +
                "${pathSegment(roleId, "roleId")}/members",
        )
        return authorizedGet(withQuery(targetEndpoint, query), QqGuildModels.RoleMembersPage::class.java)
    }

    fun getRoles(guildId: String): CompletionStage<QqGuildModels.GuildRoles> =
        authorizedGet(rolesEndpoint(guildId), QqGuildModels.GuildRoles::class.java)

    fun createRole(
        guildId: String,
        request: QqGuildModels.RoleUpdate,
    ): CompletionStage<QqGuildModels.RoleUpdateResult> {
        return authorizedPost(rolesEndpoint(guildId), request, QqGuildModels.RoleUpdateResult::class.java)
    }

    fun updateRole(
        guildId: String,
        roleId: String,
        request: QqGuildModels.RoleUpdate,
    ): CompletionStage<QqGuildModels.RoleUpdateResult> {
        return authorizedPatch(roleEndpoint(guildId, roleId), request, QqGuildModels.RoleUpdateResult::class.java)
    }

    fun deleteRole(guildId: String, roleId: String): CompletionStage<Void> =
        authorizedDelete(roleEndpoint(guildId, roleId), Void::class.java)

    fun addGuildMemberRole(
        guildId: String,
        userId: String,
        roleId: String,
        request: QqGuildModels.MemberRoleRequest?,
    ): CompletionStage<Void> = authorizedPut(
        memberRoleEndpoint(guildId, userId, roleId),
        request ?: emptyMap<String, Any>(),
        Void::class.java,
    )

    fun removeGuildMemberRole(
        guildId: String,
        userId: String,
        roleId: String,
        request: QqGuildModels.MemberRoleRequest?,
    ): CompletionStage<Void> = authorizedDelete(
        memberRoleEndpoint(guildId, userId, roleId),
        request ?: emptyMap<String, Any>(),
        Void::class.java,
    )

    fun getGuildApiPermissions(guildId: String): CompletionStage<QqPermissionModels.ApiPermissions> =
        authorizedGet(
            endpoint("guilds/${pathSegment(guildId, "guildId")}/api_permission"),
            QqPermissionModels.ApiPermissions::class.java,
        )

    fun demandGuildApiPermission(
        guildId: String,
        request: QqPermissionModels.ApiPermissionDemandRequest,
    ): CompletionStage<QqPermissionModels.ApiPermissionDemand> {
        return authorizedPost(
            endpoint("guilds/${pathSegment(guildId, "guildId")}/api_permission/demand"),
            request,
            QqPermissionModels.ApiPermissionDemand::class.java,
        )
    }

    fun getChannelMemberPermission(
        channelId: String,
        userId: String,
    ): CompletionStage<QqPermissionModels.ChannelPermission> = authorizedGet(
        channelMemberPermissionEndpoint(channelId, userId),
        QqPermissionModels.ChannelPermission::class.java,
    )

    fun updateChannelMemberPermission(
        channelId: String,
        userId: String,
        request: QqPermissionModels.ChannelPermissionUpdate,
    ): CompletionStage<Void> {
        validatePermissionUpdate(request)
        return authorizedPut(channelMemberPermissionEndpoint(channelId, userId), request, Void::class.java)
    }

    fun getChannelRolePermission(
        channelId: String,
        roleId: String,
    ): CompletionStage<QqPermissionModels.ChannelPermission> = authorizedGet(
        channelRolePermissionEndpoint(channelId, roleId),
        QqPermissionModels.ChannelPermission::class.java,
    )

    fun updateChannelRolePermission(
        channelId: String,
        roleId: String,
        request: QqPermissionModels.ChannelPermissionUpdate,
    ): CompletionStage<Void> {
        validatePermissionUpdate(request)
        return authorizedPut(channelRolePermissionEndpoint(channelId, roleId), request, Void::class.java)
    }

    fun muteGuild(guildId: String, request: QqGuildModels.MuteRequest): CompletionStage<Void> {
        validateMuteRequest(request, false)
        return authorizedPatch(guildMuteEndpoint(guildId), request, Void::class.java)
    }

    fun muteGuildMembers(
        guildId: String,
        request: QqGuildModels.MuteRequest,
    ): CompletionStage<QqGuildModels.MuteResult> {
        validateMuteRequest(request, true)
        return authorizedPatch(guildMuteEndpoint(guildId), request, QqGuildModels.MuteResult::class.java)
    }

    fun muteGuildMember(
        guildId: String,
        userId: String,
        request: QqGuildModels.MuteRequest,
    ): CompletionStage<Void> {
        validateMuteRequest(request, false)
        val targetEndpoint = URI.create("${guildMemberEndpoint(guildId, userId)}/mute")
        return authorizedPatch(targetEndpoint, request, Void::class.java)
    }

    fun getGuildMessageSetting(guildId: String): CompletionStage<QqGuildModels.MessageSetting> =
        authorizedGet(
            endpoint("guilds/${pathSegment(guildId, "guildId")}/message/setting"),
            QqGuildModels.MessageSetting::class.java,
        )

    fun getChannelOnlineMemberCount(
        channelId: String,
    ): CompletionStage<QqGuildModels.OnlineMemberCount> = authorizedGet(
        endpoint("channels/${pathSegment(channelId, "channelId")}/online_nums"),
        QqGuildModels.OnlineMemberCount::class.java,
    )

    fun addMessageReaction(
        channelId: String,
        messageId: String,
        emojiType: Int,
        emojiId: String,
    ): CompletionStage<Void> {
        validateEmojiType(emojiType)
        return authorizedPut(reactionEndpoint(channelId, messageId, emojiType, emojiId), null, Void::class.java)
    }

    fun deleteMessageReaction(
        channelId: String,
        messageId: String,
        emojiType: Int,
        emojiId: String,
    ): CompletionStage<Void> {
        validateEmojiType(emojiType)
        return authorizedDelete(reactionEndpoint(channelId, messageId, emojiType, emojiId), Void::class.java)
    }

    fun getMessageReactionUsers(
        channelId: String,
        messageId: String,
        emojiType: Int,
        emojiId: String,
        cookie: String?,
        limit: Int?,
    ): CompletionStage<QqContentModels.ReactionUsers> {
        validateEmojiType(emojiType)
        validateOptionalLimit(limit, 1, 100, "limit")
        val query = LinkedHashMap<String, Any>()
        putOptional(query, "cookie", cookie)
        putOptional(query, "limit", limit)
        return authorizedGet(
            withQuery(reactionEndpoint(channelId, messageId, emojiType, emojiId), query),
            QqContentModels.ReactionUsers::class.java,
        )
    }

    fun createGuildAnnouncement(
        guildId: String,
        request: QqContentModels.GuildAnnouncementRequest,
    ): CompletionStage<QqContentModels.Announcement> {
        return authorizedPost(
            guildAnnouncementsEndpoint(guildId),
            request,
            QqContentModels.Announcement::class.java,
        )
    }

    fun deleteGuildAnnouncement(guildId: String, messageId: String): CompletionStage<Void> =
        authorizedDelete(
            guildAnnouncementsEndpoint(guildId).resolve("announces/${pathSegment(messageId, "messageId")}"),
            Void::class.java,
        )

    fun clearGuildAnnouncements(guildId: String): CompletionStage<Void> =
        deleteGuildAnnouncement(guildId, "all")

    fun getPins(channelId: String): CompletionStage<QqContentModels.Pins> =
        authorizedGet(pinsEndpoint(channelId), QqContentModels.Pins::class.java)

    fun addPin(channelId: String, messageId: String): CompletionStage<QqContentModels.Pins> =
        authorizedPut(pinEndpoint(channelId, messageId), emptyMap<String, Any>(), QqContentModels.Pins::class.java)

    fun deletePin(channelId: String, messageId: String): CompletionStage<Void> =
        authorizedDelete(pinEndpoint(channelId, messageId), Void::class.java)

    fun clearPins(channelId: String): CompletionStage<Void> = deletePin(channelId, "all")

    fun getSchedules(channelId: String, since: Long?): CompletionStage<List<QqContentModels.Schedule>> {
        var targetEndpoint = schedulesEndpoint(channelId)
        if (since != null) {
            require(since >= 0L) { "since must not be negative" }
            targetEndpoint = withQuery(targetEndpoint, mapOf("since" to since))
        }
        return authorizedGet(targetEndpoint, Array<QqContentModels.Schedule>::class.java)
            .thenApply { immutableList(it) }
    }

    fun getSchedule(channelId: String, scheduleId: String): CompletionStage<QqContentModels.Schedule> =
        authorizedGet(scheduleEndpoint(channelId, scheduleId), QqContentModels.Schedule::class.java)

    fun createSchedule(
        channelId: String,
        request: QqContentModels.ScheduleInput,
    ): CompletionStage<QqContentModels.Schedule> {
        return authorizedPost(
            schedulesEndpoint(channelId),
            QqContentModels.ScheduleRequest(request),
            QqContentModels.Schedule::class.java,
        )
    }

    fun updateSchedule(
        channelId: String,
        scheduleId: String,
        request: QqContentModels.ScheduleInput,
    ): CompletionStage<QqContentModels.Schedule> {
        return authorizedPatch(
            scheduleEndpoint(channelId, scheduleId),
            QqContentModels.ScheduleRequest(request),
            QqContentModels.Schedule::class.java,
        )
    }

    fun deleteSchedule(channelId: String, scheduleId: String): CompletionStage<Void> =
        authorizedDelete(scheduleEndpoint(channelId, scheduleId), Void::class.java)

    fun getForumThreads(channelId: String): CompletionStage<QqContentModels.ForumThreads> =
        authorizedGet(threadsEndpoint(channelId), QqContentModels.ForumThreads::class.java)

    fun getForumThread(
        channelId: String,
        threadId: String,
    ): CompletionStage<QqContentModels.ForumThread> =
        authorizedGet(threadEndpoint(channelId, threadId), QqContentModels.ForumThread::class.java)

    fun createForumThread(
        channelId: String,
        request: QqContentModels.ForumThreadRequest,
    ): CompletionStage<QqContentModels.ForumThreadCreateResult> {
        require(request.format != null && request.format in 1..4) { "format must be between 1 and 4" }
        return authorizedPut(
            threadsEndpoint(channelId),
            request,
            QqContentModels.ForumThreadCreateResult::class.java,
        )
    }

    fun deleteForumThread(channelId: String, threadId: String): CompletionStage<Void> =
        authorizedDelete(threadEndpoint(channelId, threadId), Void::class.java)

    fun controlAudio(
        channelId: String,
        request: QqContentModels.AudioControl,
    ): CompletionStage<Void> {
        val status = request.status
        require(status != null && status in 0..3) { "audio status must be between 0 and 3" }
        return authorizedPost(
            endpoint("channels/" + pathSegment(channelId, "channelId") + "/audio"),
            request,
            Void::class.java,
        )
    }

    fun putBotOnMic(channelId: String): CompletionStage<Void> =
        authorizedPut(micEndpoint(channelId), null, Void::class.java)

    fun removeBotFromMic(channelId: String): CompletionStage<Void> =
        authorizedDelete(micEndpoint(channelId), Void::class.java)

    fun sendText(request: QqTextMessageRequest): CompletionStage<QqMessageSendResult> =
        sendText(request, QqMessageSendOptions())

    fun sendText(
        request: QqTextMessageRequest,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        validateMessageOptions(
            request.targetType,
            request.replyMessageId,
            request.replyEventId,
            sendOptions,
        )
        val targetEndpoint = messageEndpoint(request.targetType, request.targetId)
        val body = TextMessagePayload(
            request.content,
            request.replyMessageId,
            request.replyEventId,
            request.messageSequence,
            if (request.targetType == QqMessageTargetType.CHANNEL ||
                request.targetType == QqMessageTargetType.DIRECT
            ) {
                null
            } else {
                0
            },
            sendOptions.messageReference,
            if (sendOptions.wakeup) true else null,
        )
        return tokenProvider.getAccessToken().thenCompose { token ->
            transport.postAuthorizedJson(
                targetEndpoint,
                token,
                body,
                applicationHeaders,
                QqMessageSendResult::class.java,
            )
        }
    }

    fun sendMedia(request: QqMediaMessageRequest): CompletionStage<QqMessageSendResult> =
        sendMedia(request, QqMessageSendOptions())

    fun sendMedia(
        request: QqMediaMessageRequest,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        validateMessageOptions(
            request.targetType,
            request.replyMessageId,
            request.replyEventId,
            sendOptions,
        )
        if (request.targetType == QqMessageTargetType.C2C ||
            request.targetType == QqMessageTargetType.GROUP
        ) {
            val uploadEndpoint = mediaUploadEndpoint(request.targetType, request.targetId)
            val upload = MediaUploadPayload(
                request.mediaKind.fileType(),
                request.mediaUrl.toString(),
                null,
                false,
            )
            return tokenProvider.getAccessToken().thenCompose { token ->
                transport.postAuthorizedJson(
                    uploadEndpoint,
                    token,
                    upload,
                    applicationHeaders,
                    QqMediaUploadResult::class.java,
                ).thenCompose { result ->
                    val fileInfo = result.fileInfo
                    if (fileInfo == null || fileInfo.isBlank()) {
                        CompletableFuture.failedFuture(
                            QqClientException.protocol(
                                uploadEndpoint,
                                IllegalArgumentException("file_info is missing"),
                            ),
                        )
                    } else {
                        val message = MediaMessagePayload(
                            request.content,
                            MediaReference(fileInfo),
                            request.replyMessageId,
                            request.replyEventId,
                            request.messageSequence,
                            7,
                            sendOptions.messageReference,
                            if (sendOptions.wakeup) true else null,
                        )
                        transport.postAuthorizedJson(
                            messageEndpoint(request.targetType, request.targetId),
                            token,
                            message,
                            applicationHeaders,
                            QqMessageSendResult::class.java,
                        )
                    }
                }
            }
        }
        if (request.mediaKind != QqMediaKind.IMAGE) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("Channel and direct messages only support image URLs"),
            )
        }
        val payload = ChannelImagePayload(
            request.content,
            request.mediaUrl.toString(),
            request.replyMessageId,
            request.replyEventId,
            sendOptions.messageReference,
        )
        val targetEndpoint = messageEndpoint(request.targetType, request.targetId)
        return tokenProvider.getAccessToken().thenCompose { token ->
            transport.postAuthorizedJson(
                targetEndpoint,
                token,
                payload,
                applicationHeaders,
                QqMessageSendResult::class.java,
            )
        }
    }

    /** Downloads a remote URL under the configured SSRF, redirect, timeout, and size policy. */
    fun sendMediaBounded(request: QqMediaMessageRequest): CompletionStage<QqMessageSendResult> =
        sendMediaBounded(request, QqMessageSendOptions())

    fun sendMediaBounded(
        request: QqMediaMessageRequest,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        if ((request.targetType == QqMessageTargetType.CHANNEL ||
                request.targetType == QqMessageTargetType.DIRECT) &&
            request.mediaKind != QqMediaKind.IMAGE
        ) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("Channel and direct messages only support image media"),
            )
        }
        return mediaDownloader.download(request.mediaUrl)
            .thenCompose { bytes -> sendMedia(request, bytes, sendOptions) }
    }

    /** Sends caller-owned media bytes using QQ's chunked C2C/GROUP upload or channel multipart upload. */
    fun sendMedia(
        request: QqMediaMessageRequest,
        mediaBytes: ByteArray,
    ): CompletionStage<QqMessageSendResult> =
        sendMedia(request, mediaBytes, QqMessageSendOptions())

    fun sendMedia(
        request: QqMediaMessageRequest,
        mediaBytes: ByteArray,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        return sendMedia(request, ByteArrayInputStream(mediaBytes), mediaBytes.size.toLong(), "qqbot-media.bin", sendOptions)
    }

    fun sendMedia(
        request: QqMediaMessageRequest,
        media: InputStream,
        mediaSize: Long,
        fileName: String = "qqbot-media.bin",
    ): CompletionStage<QqMessageSendResult> = sendMedia(request, media, mediaSize, fileName, QqMessageSendOptions())

    fun sendMedia(
        request: QqMediaMessageRequest,
        media: () -> InputStream,
        mediaSize: Long,
        fileName: String = "qqbot-media.bin",
        sendOptions: QqMessageSendOptions = QqMessageSendOptions(),
    ): CompletionStage<QqMessageSendResult> = try {
        sendMedia(request, media(), mediaSize, fileName, sendOptions)
    } catch (exception: RuntimeException) {
        CompletableFuture.failedFuture(exception)
    }

    fun sendMedia(
        request: QqMediaMessageRequest,
        media: InputStream,
        mediaSize: Long,
        fileName: String,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        validateMessageOptions(
            request.targetType,
            request.replyMessageId,
            request.replyEventId,
            sendOptions,
        )
        if (mediaSize <= 0L) {
            return CompletableFuture.failedFuture(IllegalArgumentException("media bytes must not be empty"))
        }
        val chunkedTarget = request.targetType == QqMessageTargetType.C2C ||
            request.targetType == QqMessageTargetType.GROUP
        val maxAllowed = if (chunkedTarget) {
            minOf(options.maxMediaBytes, MAX_CHUNKED_MEDIA_BYTES)
        } else {
            options.maxMediaBytes
        }
        if (mediaSize > maxAllowed) {
            return CompletableFuture.failedFuture(
                IllegalArgumentException("media bytes exceed configured limit"),
            )
        }
        if (request.targetType == QqMessageTargetType.CHANNEL ||
            request.targetType == QqMessageTargetType.DIRECT
        ) {
            if (request.mediaKind != QqMediaKind.IMAGE) {
                return CompletableFuture.failedFuture(
                    IllegalArgumentException("Channel and direct messages only support image media"),
                )
            }
            val fields = HashMap<String, String>()
            request.content?.let { fields["content"] = it }
            request.replyMessageId?.let { fields["msg_id"] = it }
            request.replyEventId?.let { fields["event_id"] = it }
            sendOptions.messageReference?.let {
                fields["message_reference"] = jsonCodec.encode(it)
            }
            fields["msg_seq"] = request.messageSequence.toString()
            val targetEndpoint = messageEndpoint(request.targetType, request.targetId)
            return tokenProvider.getAccessToken().thenCompose { token ->
                transport.postAuthorizedMultipart(
                    targetEndpoint,
                    token,
                    applicationHeaders,
                    fields,
                    "file_image",
                    "qqbot-image.bin",
                    "application/octet-stream",
                    readExactly(media, mediaSize),
                    QqMessageSendResult::class.java,
                )
            }
        }
        if (request.targetType != QqMessageTargetType.C2C &&
            request.targetType != QqMessageTargetType.GROUP
        ) {
            return CompletableFuture.failedFuture(IllegalArgumentException("Unsupported media target"))
        }
        val uploadEndpoint = mediaUploadEndpoint(request.targetType, request.targetId)
        val chunkBase = chunkUploadBaseEndpoint(request.targetType, request.targetId)
        val cancellation = CancellationGroup()
        val token = tokenProvider.getAccessToken().toCompletableFuture()
        cancellation.track(token)
        val pipeline = token.thenCompose { accessToken ->
            val prepared = prepareChunkedUpload(
                request,
                media,
                mediaSize,
                fileName,
                accessToken,
                chunkBase,
                uploadEndpoint,
                cancellation,
            ).toCompletableFuture()
            cancellation.track(prepared)
            prepared.thenCompose { result ->
                val message = sendUploadedMedia(request, sendOptions, accessToken, uploadEndpoint, result)
                    .toCompletableFuture()
                cancellation.track(message)
                message
            }
        }
        cancellation.track(pipeline)
        val cancellable = CompletableFuture<QqMessageSendResult>()
        pipeline.whenComplete { value, error ->
            if (error != null) cancellable.completeExceptionally(unwrapCompletion(error)) else cancellable.complete(value)
        }
        cancellable.whenComplete { _, _ ->
            if (cancellable.isCancelled) {
                cancellation.cancelAll()
            }
        }
        return cancellable
    }

    private fun prepareChunkedUpload(
        request: QqMediaMessageRequest, media: InputStream, mediaSize: Long, fileName: String,
        token: AccessToken, chunkBase: URI, endpoint: URI,
        cancellation: CancellationGroup,
    ): CompletionStage<QqMediaUploadResult> {
        val preparation = CompletableFuture.supplyAsync {
            cancellation.check()
            val temp = Files.createTempFile("qqbot-media-", ".bin")
            try {
                media.use { copyMedia(it, mediaSize, temp) }
                PreparedMedia(temp, hashMedia(temp))
            } catch (exception: Throwable) {
                deleteQuietly(temp)
                throw exception
            }
        }
        cancellation.track(preparation)
        preparation.whenComplete { prepared, error ->
            if (error == null && prepared != null && cancellation.cancelled.get()) {
                deleteQuietly(prepared.path)
            }
        }
        val stage = preparation.thenCompose { prepared ->
            val preparePayload = ChunkPreparePayload(
                request.mediaKind.fileType(),
                mediaSize.toString(),
                fileName,
                prepared.hashes.md5,
                prepared.hashes.sha1,
                prepared.hashes.md510m,
            )
            val prepareCall = transport.postAuthorizedJson(
                URI.create("$chunkBase/upload_prepare"),
                token,
                preparePayload,
                applicationHeaders,
                ChunkPrepareResponse::class.java,
            )
            cancellation.track(prepareCall)
            val upload = prepareCall.thenCompose { response ->
                val plan = validateChunkPrepare(response, mediaSize, endpoint)
                uploadChunks(
                    plan,
                    prepared.path,
                    token,
                    chunkBase,
                    endpoint,
                    request.mediaKind.fileType(),
                    fileName,
                    cancellation,
                )
            }
            cancellation.track(upload)
            upload.whenComplete { _, _ -> deleteQuietly(prepared.path) }
            upload
        }
        cancellation.track(stage)
        return stage
    }

    private fun validateChunkPrepare(
        response: ChunkPrepareResponse,
        mediaSize: Long,
        endpoint: URI,
    ): ChunkUploadPlan {
        try {
            val uploadId = requireNotNull(response.uploadId?.takeIf { it.isNotBlank() }) {
                "upload_id is missing"
            }
            val blockSize = parseSize(response.blockSize, "block_size")
            val config = requireNotNull(response.uploadConfig) { "upload_config is missing" }
            require(config.concurrency in 1..MAX_UPLOAD_CONCURRENCY) {
                "upload_config.concurrency is invalid"
            }
            require(config.retryTimeout >= 0L) { "retry_timeout must not be negative" }
            require(config.retryDelay >= 0L) { "retry_delay must not be negative" }
            val rawParts = requireNotNull(response.parts).takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("parts is empty")
            val indexes = HashSet<Int>()
            val parts = rawParts.map { raw ->
                val index = requireNotNull(raw.index) { "part index is missing" }
                require(index >= 0 && indexes.add(index)) { "part indexes must be unique and non-negative" }
                val size = parseSize(raw.blockSize, "parts.block_size")
                require(size <= blockSize) { "part size exceeds block_size" }
                val url = URI.create(requireNotNull(raw.presignedUrl?.takeIf { it.isNotBlank() }) {
                    "parts.presigned_url is missing"
                })
                require(
                    url.userInfo == null && url.fragment == null &&
                        (url.scheme.equals("https", true) ||
                            (url.scheme.equals("http", true) && isLoopback(url.host))),
                ) { "presigned URL must use HTTPS" }
                ChunkPartPlan(index, size, url, 0L)
            }.sortedBy { it.index }
            val partsWithOffsets = parts.mapIndexed { expectedIndex, part ->
                require(part.index == expectedIndex) { "part indexes must be contiguous from zero" }
                val offset = Math.multiplyExact(part.index.toLong(), blockSize.toLong())
                val remaining = mediaSize - offset
                require(remaining > 0L) { "parts do not cover the declared media size" }
                val expectedSize = minOf(blockSize.toLong(), remaining).toInt()
                require(part.size == expectedSize) { "parts do not cover the declared media size" }
                part.copy(offset = offset)
            }
            require(partsWithOffsets.last().offset + partsWithOffsets.last().size == mediaSize) {
                "parts do not cover the declared media size"
            }
            return ChunkUploadPlan(
                uploadId,
                partsWithOffsets,
                config.concurrency,
                config.retryTimeout,
                config.retryDelay,
            )
        } catch (exception: RuntimeException) {
            throw QqClientException.protocol(endpoint, exception)
        }
    }

    private fun uploadChunks(
        plan: ChunkUploadPlan,
        file: java.nio.file.Path,
        token: AccessToken,
        chunkBase: URI,
        endpoint: URI,
        fileType: Int,
        fileName: String,
        cancellation: CancellationGroup,
    ): CompletionStage<QqMediaUploadResult> {
        val executor = Executors.newFixedThreadPool(
            minOf(plan.concurrency, plan.parts.size),
            { runnable -> Thread(runnable, "qqbot-media-upload").apply { isDaemon = true } },
        )
        val uploads = plan.parts.map { part ->
            CompletableFuture.runAsync({
                cancellation.check()
                val bytes = readPart(file, part)
                awaitTracked(
                    retryPresigned(part.url, bytes, plan.retryTimeout, plan.retryDelay, cancellation),
                    cancellation,
                )
            }, executor).also { future ->
                cancellation.track(future)
            }
        }
        val allUploaded = CompletableFuture.allOf(*uploads.toTypedArray())
        cancellation.track(allUploaded)
        allUploaded.whenComplete { _, error -> if (error != null) cancellation.cancelAll() }
        val finished = allUploaded.thenCompose {
            var stage: CompletionStage<Void> = CompletableFuture.completedFuture(null)
            plan.parts.forEach { part ->
                stage = stage.thenCompose {
                    cancellation.check()
                    val bytes = readPart(file, part)
                    val md5 = MessageDigest.getInstance("MD5").digest(bytes).toHex()
                    val finish = retryPartFinish(
                        URI.create("$chunkBase/upload_part_finish"), token,
                        PartFinishPayload(plan.uploadId, part.index, part.size.toString(), md5),
                        plan.retryTimeout, plan.retryDelay, cancellation,
                    )
                    cancellation.track(finish)
                    finish.whenComplete { _, _ -> cancellation.untrack(finish) }
                }
            }
            stage
        }
        cancellation.track(finished)
        val merged = finished.thenCompose {
            cancellation.check()
            transport.postAuthorizedJson(
                endpoint,
                token,
                MergePayload(fileType, false, fileName, plan.uploadId),
                applicationHeaders,
                QqMediaUploadResult::class.java,
            )
        }
        cancellation.track(merged)
        val result = merged.toCompletableFuture()
        result.whenComplete { _, _ ->
            executor.shutdownNow()
            if (result.isCancelled) cancellation.cancelAll()
        }
        return result
    }

    private fun retryPartFinish(
        endpoint: URI, token: AccessToken, payload: PartFinishPayload,
        timeoutSeconds: Long, delaySeconds: Long, cancellation: CancellationGroup,
    ): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        val timeoutNanos = secondsToNanos(timeoutSeconds)
        val startedAt = System.nanoTime()
        fun attempt() {
            if (result.isDone) return
            try {
                cancellation.check()
                val call = transport.postAuthorizedJson(endpoint, token, payload, applicationHeaders, Void::class.java)
                cancellation.track(call)
                call.whenComplete { _, error ->
                    cancellation.untrack(call)
                    if (error == null) {
                        result.complete(null)
                    } else if (cancellation.cancelled.get()) {
                        result.cancel(false)
                    } else {
                        val cause = unwrapCompletion(error)
                        val retryable = (cause as? QqClientException)?.qqCode == 40093001
                        val elapsed = System.nanoTime() - startedAt
                        val remaining = timeoutNanos - elapsed
                        if (!retryable || timeoutSeconds <= 0L || remaining <= 0L) {
                            result.completeExceptionally(cause)
                        } else {
                            val delayNanos = minOf(secondsToNanos(delaySeconds), remaining).coerceAtLeast(0L)
                            if (delayNanos >= remaining && delayNanos > 0L) {
                                result.completeExceptionally(cause)
                            } else {
                                CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute {
                                    if (System.nanoTime() - startedAt >= timeoutNanos) {
                                        result.completeExceptionally(cause)
                                    } else {
                                        attempt()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                result.cancel(false)
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) cancellation.cancelAll() }
        attempt()
        return result
    }

    private fun retryPresigned(
        uri: URI,
        bytes: ByteArray,
        timeoutSeconds: Long,
        delaySeconds: Long,
        cancellation: CancellationGroup,
    ): CompletionStage<Void> {
        val result = CompletableFuture<Void>()
        val timeoutNanos = secondsToNanos(timeoutSeconds)
        val startedAt = System.nanoTime()
        fun attempt() {
            if (result.isDone) return
            try {
                cancellation.check()
                val put = transport.putPresigned(uri, bytes)
                cancellation.track(put)
                put.whenComplete { _, error ->
                    cancellation.untrack(put)
                    if (error == null) {
                        result.complete(null)
                    } else if (cancellation.cancelled.get()) {
                        result.cancel(false)
                    } else if (timeoutSeconds <= 0L || System.nanoTime() - startedAt >= timeoutNanos) {
                        result.completeExceptionally(unwrapCompletion(error))
                    } else {
                        val remaining = timeoutNanos - (System.nanoTime() - startedAt)
                        val delayNanos = minOf(secondsToNanos(delaySeconds), remaining).coerceAtLeast(0L)
                        if (delayNanos >= remaining && delayNanos > 0L) {
                            result.completeExceptionally(unwrapCompletion(error))
                        } else {
                            CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute {
                                if (System.nanoTime() - startedAt >= timeoutNanos) {
                                    result.completeExceptionally(unwrapCompletion(error))
                                } else {
                                    attempt()
                                }
                            }
                        }
                    }
                }
            } catch (exception: CancellationException) {
                result.cancel(false)
            }
        }
        result.whenComplete { _, _ -> if (result.isCancelled) cancellation.cancelAll() }
        attempt()
        return result
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (value in this@toHex) {
            val unsigned = value.toInt() and 0xff
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0f])
        }
    }

    private fun copyMedia(media: InputStream, expectedSize: Long, target: java.nio.file.Path) {
        Files.newOutputStream(target, StandardOpenOption.WRITE).use { output ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = media.read(buffer)
                if (read == -1) break
                total = Math.addExact(total, read.toLong())
                require(total <= expectedSize) { "media stream is larger than declared size" }
                output.write(buffer, 0, read)
            }
            require(total == expectedSize) { "declared media size does not match stream" }
        }
    }

    private fun hashMedia(path: java.nio.file.Path): MediaHashes {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val first = MessageDigest.getInstance("MD5")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                md5.update(buffer, 0, read)
                sha1.update(buffer, 0, read)
                if (total < MD5_10M_BYTES) {
                    val count = minOf(read.toLong(), MD5_10M_BYTES - total).toInt()
                    first.update(buffer, 0, count)
                }
                total += read
            }
        }
        return MediaHashes(md5.digest().toHex(), sha1.digest().toHex(), first.digest().toHex())
    }

    private fun readPart(path: java.nio.file.Path, part: ChunkPartPlan): ByteArray {
        val buffer = ByteBuffer.allocate(part.size)
        Files.newByteChannel(path, StandardOpenOption.READ).use { channel ->
            channel.position(part.offset)
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer)
                require(read >= 0) { "media stream ended before declared part size" }
            }
        }
        return buffer.array()
    }

    private fun readExactly(media: InputStream, expectedSize: Long): ByteArray {
        require(expectedSize <= Int.MAX_VALUE) { "media bytes are too large for multipart upload" }
        val bytes = media.readNBytes(expectedSize.toInt())
        require(bytes.size.toLong() == expectedSize) { "declared media size does not match stream" }
        require(media.read() == -1) { "media stream is larger than declared size" }
        return bytes
    }

    private fun parseSize(value: String?, name: String): Int {
        val parsed = requireNonNegative(value, name)
        require(parsed in 1..Int.MAX_VALUE.toLong()) { "$name is invalid" }
        return parsed.toInt()
    }

    private fun requireNonNegative(value: String?, name: String): Long = try {
        requireNotNull(value?.trim()?.takeIf { it.isNotEmpty() }) { "$name is missing" }
            .toLong()
            .also { require(it >= 0L) { "$name must not be negative" } }
    } catch (exception: NumberFormatException) {
        throw IllegalArgumentException("$name is invalid", exception)
    }

    private fun secondsToNanos(seconds: Long): Long {
        if (seconds <= 0L) return 0L
        return try {
            Math.multiplyExact(seconds, 1_000_000_000L)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }

    private fun unwrapCompletion(error: Throwable): Throwable {
        var current = error
        while ((current is java.util.concurrent.CompletionException ||
                current is java.util.concurrent.ExecutionException) && current.cause != null
        ) {
            current = current.cause!!
        }
        return current
    }

    private fun deleteQuietly(path: java.nio.file.Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: java.io.IOException) {
            // Best-effort cleanup; the upload result remains authoritative.
        }
    }

    private fun isLoopback(host: String?) = host == "127.0.0.1" || host == "localhost" || host == "::1"

    private fun <T> awaitTracked(stage: CompletionStage<T>, cancellation: CancellationGroup): T {
        val future = stage.toCompletableFuture()
        cancellation.track(future)
        return try {
            future.join()
        } finally {
            cancellation.untrack(future)
        }
    }

    private class CancellationGroup {
        val cancelled = AtomicBoolean()
        private val stages = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

        fun track(stage: CompletionStage<*>) {
            stages += stage.toCompletableFuture()
            if (cancelled.get()) stage.toCompletableFuture().cancel(true)
        }

        fun untrack(stage: CompletionStage<*>) {
            stages -= stage.toCompletableFuture()
        }

        fun check() {
            if (cancelled.get()) throw CancellationException("media upload was cancelled")
        }

        fun cancelAll() {
            if (cancelled.compareAndSet(false, true)) {
                stages.forEach { it.cancel(true) }
            }
        }
    }

    private data class MediaHashes(val md5: String, val sha1: String, val md510m: String)

    private data class PreparedMedia(val path: java.nio.file.Path, val hashes: MediaHashes)

    private data class ChunkUploadPlan(
        val uploadId: String,
        val parts: List<ChunkPartPlan>,
        val concurrency: Int,
        val retryTimeout: Long,
        val retryDelay: Long,
    )

    private data class ChunkPartPlan(val index: Int, val size: Int, val url: URI, val offset: Long)

    fun sendMarkdown(request: QqMarkdownMessageRequest): CompletionStage<QqMessageSendResult> {
        val markdown = HashMap<String, Any>()
        markdown["content"] = request.content
        request.customTemplateId?.let { markdown["custom_template_id"] = it }
        if (request.params.isNotEmpty()) {
            markdown["params"] = request.params.entries.map {
                mapOf<String, Any>("key" to it.key, "values" to listOf(it.value))
            }
        }
        return sendRich(
            QqRichMessageRequest(
                request.targetType,
                request.targetId,
                QqRichMessageKind.MARKDOWN,
                markdown,
                request.replyMessageId,
                request.replyEventId,
                request.messageSequence,
            ),
        )
    }

    fun sendKeyboard(request: QqKeyboardMessageRequest): CompletionStage<QqMessageSendResult> {
        val keyboard = HashMap<String, Any>()
        request.keyboardId?.let { keyboard["id"] = it }
        if (request.rows.isNotEmpty()) {
            keyboard["content"] = mapOf("rows" to request.rows)
        }
        val composite = mapOf<String, Any>(
            "markdown" to mapOf("content" to request.markdownContent),
            "keyboard" to keyboard,
        )
        return sendRich(
            QqRichMessageRequest(
                request.targetType,
                request.targetId,
                QqRichMessageKind.KEYBOARD,
                composite,
                request.replyMessageId,
                request.replyEventId,
                request.messageSequence,
            ),
        )
    }

    fun sendArk(request: QqArkMessageRequest): CompletionStage<QqMessageSendResult> {
        val ark = mapOf<String, Any>("template_id" to request.templateId, "kv" to request.values)
        return sendRich(
            QqRichMessageRequest(
                request.targetType,
                request.targetId,
                QqRichMessageKind.ARK,
                ark,
                request.replyMessageId,
                request.replyEventId,
                request.messageSequence,
            ),
        )
    }

    fun sendEmbed(request: QqEmbedMessageRequest): CompletionStage<QqMessageSendResult> {
        val embed = HashMap<String, Any>()
        embed["title"] = request.title
        request.prompt?.let { embed["prompt"] = it }
        if (request.fields.isNotEmpty()) {
            embed["fields"] = request.fields
        }
        return sendRich(
            QqRichMessageRequest(
                request.targetType,
                request.targetId,
                QqRichMessageKind.EMBED,
                embed,
                request.replyMessageId,
                request.replyEventId,
                request.messageSequence,
            ),
        )
    }

    fun sendRich(request: QqRichMessageRequest): CompletionStage<QqMessageSendResult> =
        sendRich(request, QqMessageSendOptions())

    fun sendRich(
        request: QqRichMessageRequest,
        sendOptions: QqMessageSendOptions,
    ): CompletionStage<QqMessageSendResult> {
        validateMessageOptions(
            request.targetType,
            request.replyMessageId,
            request.replyEventId,
            sendOptions,
        )
        val key = request.kind.name.lowercase(Locale.ROOT)
        val body = HashMap<String, Any?>()
        if (request.kind == QqRichMessageKind.KEYBOARD) {
            body.putAll(request.payload)
        } else {
            body[key] = request.payload
        }
        body["msg_id"] = request.replyMessageId
        body["event_id"] = request.replyEventId
        body["msg_seq"] = request.messageSequence
        body["message_reference"] = sendOptions.messageReference
        body["is_wakeup"] = if (sendOptions.wakeup) true else null
        if (request.targetType == QqMessageTargetType.C2C ||
            request.targetType == QqMessageTargetType.GROUP
        ) {
            body["msg_type"] = when (request.kind) {
                QqRichMessageKind.MARKDOWN -> 2
                QqRichMessageKind.ARK -> 3
                QqRichMessageKind.EMBED -> 4
                QqRichMessageKind.KEYBOARD -> 2
            }
        }
        val targetEndpoint = messageEndpoint(request.targetType, request.targetId)
        return tokenProvider.getAccessToken().thenCompose { token ->
            transport.postAuthorizedJson(
                targetEndpoint,
                token,
                body,
                applicationHeaders,
                QqMessageSendResult::class.java,
            )
        }
    }

    private fun sendUploadedMedia(
        request: QqMediaMessageRequest,
        sendOptions: QqMessageSendOptions,
        token: AccessToken,
        uploadEndpoint: URI,
        result: QqMediaUploadResult,
    ): CompletionStage<QqMessageSendResult> {
        val fileInfo = result.fileInfo
        if (fileInfo == null || fileInfo.isBlank()) {
            return CompletableFuture.failedFuture(
                QqClientException.protocol(
                    uploadEndpoint,
                    IllegalArgumentException("file_info is missing"),
                ),
            )
        }
        val message = MediaMessagePayload(
            request.content,
            MediaReference(fileInfo),
            request.replyMessageId,
            request.replyEventId,
            request.messageSequence,
            7,
            sendOptions.messageReference,
            if (sendOptions.wakeup) true else null,
        )
        return transport.postAuthorizedJson(
            messageEndpoint(request.targetType, request.targetId),
            token,
            message,
            applicationHeaders,
            QqMessageSendResult::class.java,
        )
    }

    private fun <T> authorizedGet(targetEndpoint: URI, responseType: Class<T>): CompletionStage<T> =
        tokenProvider.getAccessToken().thenCompose { token ->
            transport.getJson(targetEndpoint, token, applicationHeaders, responseType)
        }

    private fun <T> authorizedPost(
        targetEndpoint: URI,
        body: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = tokenProvider.getAccessToken().thenCompose { token ->
        transport.postAuthorizedJson(targetEndpoint, token, body, applicationHeaders, responseType)
    }

    private fun <T> authorizedPut(
        targetEndpoint: URI,
        body: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = authorizedPut(targetEndpoint, body, applicationHeaders, responseType)

    private fun <T> authorizedPut(
        targetEndpoint: URI,
        body: Any?,
        headers: Map<String, String>,
        responseType: Class<T>,
    ): CompletionStage<T> = tokenProvider.getAccessToken().thenCompose { token ->
        transport.putAuthorizedJson(targetEndpoint, token, body, headers, responseType)
    }

    private fun <T> authorizedPatch(
        targetEndpoint: URI,
        body: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = tokenProvider.getAccessToken().thenCompose { token ->
        transport.patchAuthorizedJson(targetEndpoint, token, body, applicationHeaders, responseType)
    }

    private fun <T> authorizedDelete(
        targetEndpoint: URI,
        responseType: Class<T>,
    ): CompletionStage<T> = tokenProvider.getAccessToken().thenCompose { token ->
        transport.deleteAuthorized(targetEndpoint, token, applicationHeaders, responseType)
    }

    private fun <T> authorizedDelete(
        targetEndpoint: URI,
        body: Any?,
        responseType: Class<T>,
    ): CompletionStage<T> = tokenProvider.getAccessToken().thenCompose { token ->
        transport.deleteAuthorizedJson(targetEndpoint, token, body, applicationHeaders, responseType)
    }

    private fun toGatewayBotInfo(
        response: GatewayBotProtocolResponse,
        targetEndpoint: URI,
    ): GatewayBotInfo {
        try {
            val limit = requireNotNull(response.session_start_limit) {
                "session_start_limit must not be null"
            }
            return GatewayBotInfo(
                parseGatewayUri(response.url, targetEndpoint),
                response.shards,
                SessionStartLimit(
                    limit.total,
                    limit.remaining,
                    Duration.ofMillis(limit.reset_after),
                    limit.max_concurrency,
                ),
            )
        } catch (exception: IllegalArgumentException) {
            throw QqClientException.protocol(targetEndpoint, exception)
        }
    }

    private fun toBotProfile(response: CurrentBotUser, targetEndpoint: URI): BotProfile {
        try {
            val id = requireNotNull(response.id) { "id must not be null" }
            val username = requireNotNull(response.username) { "username must not be null" }
            val avatar = response.avatar
            return if (avatar == null || avatar.isBlank()) {
                BotProfile(id, username)
            } else {
                BotProfile(id, username, URI.create(avatar))
            }
        } catch (exception: IllegalArgumentException) {
            throw QqClientException.protocol(targetEndpoint, exception)
        }
    }

    private fun parseGatewayUri(value: String?, targetEndpoint: URI): URI {
        try {
            val uri = URI.create(requireNotNull(value) { "url must not be null" })
            GatewayBotInfo(uri, 1, SessionStartLimit(1, 1, Duration.ZERO, 1))
            return uri
        } catch (exception: IllegalArgumentException) {
            throw QqClientException.protocol(targetEndpoint, exception)
        }
    }

    private fun endpoint(path: String): URI = options.openApiBaseUri.resolve(path)

    private fun messageEndpoint(type: QqMessageTargetType, id: String): URI {
        val encoded = pathSegment(id, "targetId")
        return when (type) {
            QqMessageTargetType.C2C -> endpoint("v2/users/$encoded/messages")
            QqMessageTargetType.GROUP -> endpoint("v2/groups/$encoded/messages")
            QqMessageTargetType.CHANNEL -> endpoint("channels/$encoded/messages")
            QqMessageTargetType.DIRECT -> endpoint("dms/$encoded/messages")
        }
    }

    private fun messageResourceEndpoint(
        type: QqMessageTargetType,
        targetId: String,
        messageId: String,
    ): URI = URI.create(
        messageEndpoint(type, targetId).toString() + "/" + pathSegment(messageId, "messageId"),
    )

    private fun mediaUploadEndpoint(type: QqMessageTargetType, id: String): URI {
        val encoded = pathSegment(id, "targetId")
        return when (type) {
            QqMessageTargetType.C2C -> endpoint("v2/users/$encoded/files")
            QqMessageTargetType.GROUP -> endpoint("v2/groups/$encoded/files")
            QqMessageTargetType.CHANNEL,
            QqMessageTargetType.DIRECT,
            -> throw IllegalArgumentException("This target does not use media pre-upload")
        }
    }

    private fun chunkUploadBaseEndpoint(type: QqMessageTargetType, id: String): URI {
        val encoded = pathSegment(id, "targetId")
        return when (type) {
            QqMessageTargetType.C2C -> endpoint("v2/users/$encoded")
            QqMessageTargetType.GROUP -> endpoint("v2/groups/$encoded")
            else -> throw IllegalArgumentException("This target does not use chunked upload")
        }
    }

    private fun guildMemberEndpoint(guildId: String, userId: String): URI =
        endpoint(
            "guilds/" + pathSegment(guildId, "guildId") + "/members/" +
                pathSegment(userId, "userId"),
        )

    private fun rolesEndpoint(guildId: String): URI =
        endpoint("guilds/" + pathSegment(guildId, "guildId") + "/roles")

    private fun roleEndpoint(guildId: String, roleId: String): URI =
        URI.create(rolesEndpoint(guildId).toString() + "/" + pathSegment(roleId, "roleId"))

    private fun memberRoleEndpoint(guildId: String, userId: String, roleId: String): URI =
        URI.create(
            guildMemberEndpoint(guildId, userId).toString() + "/roles/" +
                pathSegment(roleId, "roleId"),
        )

    private fun channelMemberPermissionEndpoint(channelId: String, userId: String): URI =
        endpoint(
            "channels/" + pathSegment(channelId, "channelId") + "/members/" +
                pathSegment(userId, "userId") + "/permissions",
        )

    private fun channelRolePermissionEndpoint(channelId: String, roleId: String): URI =
        endpoint(
            "channels/" + pathSegment(channelId, "channelId") + "/roles/" +
                pathSegment(roleId, "roleId") + "/permissions",
        )

    private fun guildMuteEndpoint(guildId: String): URI =
        endpoint("guilds/" + pathSegment(guildId, "guildId") + "/mute")

    private fun reactionEndpoint(
        channelId: String,
        messageId: String,
        emojiType: Int,
        emojiId: String,
    ): URI = endpoint(
        "channels/" + pathSegment(channelId, "channelId") + "/messages/" +
            pathSegment(messageId, "messageId") + "/reactions/$emojiType/" +
            pathSegment(emojiId, "emojiId"),
    )

    private fun guildAnnouncementsEndpoint(guildId: String): URI =
        endpoint("guilds/" + pathSegment(guildId, "guildId") + "/announces")

    private fun pinsEndpoint(channelId: String): URI =
        endpoint("channels/" + pathSegment(channelId, "channelId") + "/pins")

    private fun pinEndpoint(channelId: String, messageId: String): URI =
        URI.create(pinsEndpoint(channelId).toString() + "/" + pathSegment(messageId, "messageId"))

    private fun schedulesEndpoint(channelId: String): URI =
        endpoint("channels/" + pathSegment(channelId, "channelId") + "/schedules")

    private fun scheduleEndpoint(channelId: String, scheduleId: String): URI =
        URI.create(schedulesEndpoint(channelId).toString() + "/" + pathSegment(scheduleId, "scheduleId"))

    private fun threadsEndpoint(channelId: String): URI =
        endpoint("channels/" + pathSegment(channelId, "channelId") + "/threads")

    private fun threadEndpoint(channelId: String, threadId: String): URI =
        URI.create(threadsEndpoint(channelId).toString() + "/" + pathSegment(threadId, "threadId"))

    private fun micEndpoint(channelId: String): URI =
        endpoint("channels/" + pathSegment(channelId, "channelId") + "/mic")

    private fun withQuery(targetEndpoint: URI, values: Map<String, *>): URI {
        if (values.isEmpty()) {
            return targetEndpoint
        }
        val query = StringJoiner("&")
        values.forEach { (name, value) ->
            if (value != null && (value !is String || value.isNotBlank())) {
                query.add(encodeQuery(name) + "=" + encodeQuery(value.toString()))
            }
        }
        return if (query.length() == 0) {
            targetEndpoint
        } else {
            URI.create(
                targetEndpoint.toString() +
                    (if (targetEndpoint.query == null) "?" else "&") +
                    query,
            )
        }
    }

    private fun pathSegment(value: String, name: String): String {
        require(value.isNotBlank() && value == value.trim() &&
            value.codePoints().noneMatch { Character.isWhitespace(it) } &&
            value.codePoints().noneMatch { Character.isISOControl(it) }) {
            "$name is invalid"
        }
        return encodeQuery(value)
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun putOptional(target: MutableMap<String, Any>, name: String, value: Any?) {
        if (value is String) {
            normalizeOptional(value)?.let { target[name] = it }
        } else if (value != null) {
            target[name] = value
        }
    }

    private fun normalizeOptional(value: String?): String? =
        if (value == null || value.isBlank()) null else value.trim()

    private fun validateOptionalLimit(value: Int?, minimum: Int, maximum: Int, name: String) {
        require(value == null || value in minimum..maximum) {
            "$name must be between $minimum and $maximum"
        }
    }

    private fun validateEmojiType(emojiType: Int) {
        require(emojiType == 1 || emojiType == 2) { "emojiType must be 1 or 2" }
    }

    private fun validatePermissionUpdate(request: QqPermissionModels.ChannelPermissionUpdate) {
        validateUnsignedPermission(request.add, "add")
        validateUnsignedPermission(request.remove, "remove")
        require(normalizeOptional(request.add) != null || normalizeOptional(request.remove) != null) {
            "add or remove must be set"
        }
    }

    private fun validateUnsignedPermission(value: String?, name: String) {
        if (normalizeOptional(value) == null) {
            return
        }
        try {
            java.lang.Long.parseUnsignedLong(value)
        } catch (exception: NumberFormatException) {
            throw IllegalArgumentException("$name must be an unsigned integer", exception)
        }
    }

    private fun validateMuteRequest(request: QqGuildModels.MuteRequest, requireUsers: Boolean) {
        require(normalizeOptional(request.muteEndTimestamp) != null ||
            normalizeOptional(request.muteSeconds) != null) {
            "muteEndTimestamp or muteSeconds must be set"
        }
        require(!requireUsers || !request.userIds.isNullOrEmpty()) {
            "userIds must not be empty for a batch mute"
        }
    }

    private fun validateMessageOptions(
        targetType: QqMessageTargetType,
        replyMessageId: String?,
        replyEventId: String?,
        sendOptions: QqMessageSendOptions,
    ) {
        if (!sendOptions.wakeup) {
            return
        }
        require(targetType == QqMessageTargetType.C2C) {
            "is_wakeup is only supported for C2C messages"
        }
        require(replyMessageId == null && replyEventId == null) {
            "is_wakeup cannot be combined with msg_id or event_id"
        }
    }

    private fun <T> immutableList(values: Array<T>?): List<T> =
        requireNotNull(values) { "QQ response array must not be null" }.toList()

    private data class TextMessagePayload(
        val content: String,
        @JsonProperty("msg_id") val messageId: String?,
        @JsonProperty("event_id") val eventId: String?,
        @JsonProperty("msg_seq") val messageSequence: Int?,
        @JsonProperty("msg_type") val messageType: Int?,
        @JsonProperty("message_reference") val messageReference: QqMessageModels.MessageReference?,
        @JsonProperty("is_wakeup") val wakeup: Boolean?,
    )

    private data class MediaUploadPayload(
        @JsonProperty("file_type") val fileType: Int,
        val url: String?,
        @JsonProperty("file_data") val fileData: String?,
        @JsonProperty("srv_send_msg") val serverSendMessage: Boolean,
    )

    private data class ChunkPreparePayload(
        @JsonProperty("file_type") val fileType: Int,
        @JsonProperty("file_size") val fileSize: String,
        @JsonProperty("file_name") val fileName: String,
        val md5: String,
        val sha1: String,
        @JsonProperty("md5_10m") val md510m: String,
    )

    private data class ChunkPrepareResponse(
        @JsonProperty("upload_id") val uploadId: String?,
        @JsonProperty("block_size") val blockSize: String?,
        val parts: List<ChunkPart>?,
        @JsonProperty("upload_config") val uploadConfig: UploadConfig?,
    )

    private data class UploadConfig(
        val concurrency: Int,
        @JsonProperty("retry_timeout") val retryTimeout: Long,
        @JsonProperty("retry_delay") val retryDelay: Long,
    )

    private data class ChunkPart(
        val index: Int?,
        @JsonProperty("presigned_url") val presignedUrl: String?,
        @JsonProperty("block_size") val blockSize: String?,
    )

    private data class PartFinishPayload(
        @JsonProperty("upload_id") val uploadId: String,
        @JsonProperty("part_index") val partIndex: Int,
        @JsonProperty("block_size") val blockSize: String,
        val md5: String,
    )
    private data class MergePayload(@JsonProperty("file_type") val fileType: Int, @JsonProperty("srv_send_msg") val serverSendMessage: Boolean, @JsonProperty("file_name") val fileName: String = "qqbot-media.bin", @JsonProperty("upload_id") val uploadId: String)

    private data class MediaReference(
        @JsonProperty("file_info") val fileInfo: String,
    )

    private data class MediaMessagePayload(
        val content: String?,
        val media: MediaReference,
        @JsonProperty("msg_id") val messageId: String?,
        @JsonProperty("event_id") val eventId: String?,
        @JsonProperty("msg_seq") val messageSequence: Int,
        @JsonProperty("msg_type") val messageType: Int,
        @JsonProperty("message_reference") val messageReference: QqMessageModels.MessageReference?,
        @JsonProperty("is_wakeup") val wakeup: Boolean?,
    )

    private data class ChannelImagePayload(
        val content: String?,
        val image: String,
        @JsonProperty("msg_id") val messageId: String?,
        @JsonProperty("event_id") val eventId: String?,
        @JsonProperty("message_reference") val messageReference: QqMessageModels.MessageReference?,
    )

    companion object {
        private const val GATEWAY_PATH = "/gateway"
        private const val GATEWAY_BOT_PATH = "/gateway/bot"
        private const val CURRENT_BOT_PATH = "/users/@me"
        private const val MAX_CHUNKED_MEDIA_BYTES = 200L * 1024L * 1024L
        private const val MAX_UPLOAD_CONCURRENCY = 64
        private const val MD5_10M_BYTES = 10_002_432L
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
