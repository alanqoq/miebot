package com.mieai.qqbot.client;

import com.mieai.qqbot.domain.bot.BotProfile;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.protocol.gateway.GatewayUrlResponse;
import com.mieai.qqbot.protocol.json.JsonCodec;
import com.mieai.qqbot.protocol.json.JsonCodecs;
import com.mieai.qqbot.protocol.openapi.QqContentModels;
import com.mieai.qqbot.protocol.openapi.QqGuildModels;
import com.mieai.qqbot.protocol.openapi.QqMessageModels;
import com.mieai.qqbot.protocol.openapi.QqPermissionModels;
import com.mieai.qqbot.protocol.user.CurrentBotUser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CompletionStage;

/** Asynchronous client for current, bot-scoped QQ OpenAPI operations. */
public final class QqOpenApiClient {
    private static final String GATEWAY_PATH = "/gateway";
    private static final String GATEWAY_BOT_PATH = "/gateway/bot";
    private static final String CURRENT_BOT_PATH = "/users/@me";

    private final QqClientOptions options;
    private final TokenProvider tokenProvider;
    private final JsonCodec jsonCodec;
    private final JdkQqHttpTransport transport;
    private final QqMediaDownloader mediaDownloader;
    private final String appId;
    private final Map<String, String> applicationHeaders;

    public QqOpenApiClient(QqClientOptions options, TokenProvider tokenProvider) {
        this(options, tokenProvider, null);
    }

    public QqOpenApiClient(
            QqClientOptions options, TokenProvider tokenProvider, QqAppId appId) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider must not be null");
        this.appId = appId == null ? null : appId.value();
        applicationHeaders = appId == null
                ? Map.of()
                : Map.of("X-Union-Appid", appId.value());
        jsonCodec = JsonCodecs.defaultCodec();
        transport = new JdkQqHttpTransport(jsonCodec, options.requestTimeout());
        mediaDownloader = new QqMediaDownloader(options.maxMediaBytes(), options.mediaDownloadTimeout(),
                options.maxMediaRedirects());
    }

    public CompletionStage<URI> getGateway() {
        URI endpoint = endpoint(GATEWAY_PATH);
        return authorizedGet(endpoint, GatewayUrlResponse.class)
                .thenApply(response -> parseGatewayUri(response.url(), endpoint));
    }

    public CompletionStage<GatewayBotInfo> getGatewayBot() {
        URI endpoint = endpoint(GATEWAY_BOT_PATH);
        return authorizedGet(endpoint, GatewayBotProtocolResponse.class)
                .thenApply(response -> toGatewayBotInfo(response, endpoint));
    }

    public CompletionStage<BotProfile> getCurrentBot() {
        URI endpoint = endpoint(CURRENT_BOT_PATH);
        return authorizedGet(endpoint, CurrentBotUser.class)
                .thenApply(response -> toBotProfile(response, endpoint));
    }

    public CompletionStage<Void> recallMessage(
            QqMessageTargetType targetType,
            String targetId,
            String messageId,
            boolean hideTip) {
        Objects.requireNonNull(targetType, "targetType must not be null");
        URI endpoint = messageResourceEndpoint(targetType, targetId, messageId);
        if (hideTip) {
            endpoint = withQuery(endpoint, Map.of("hidetip", true));
        }
        return authorizedDelete(endpoint, Void.class);
    }

    public CompletionStage<QqMessageModels.DirectMessage> createDirectMessage(
            QqMessageModels.DirectMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(endpoint("users/@me/dms"), request,
                QqMessageModels.DirectMessage.class);
    }

    public CompletionStage<Void> respondToInteraction(String interactionId, int code) {
        if (code < 0 || code > 5) {
            throw new IllegalArgumentException("code must be between 0 and 5");
        }
        if (appId == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("An AppID is required to respond to interactions"));
        }
        Map<String, String> headers = new HashMap<>(applicationHeaders);
        headers.put("X-Callback-AppID", appId);
        return authorizedPut(endpoint("interactions/" + pathSegment(interactionId, "interactionId")),
                new QqMessageModels.InteractionResponse(code), Map.copyOf(headers), Void.class);
    }

    public CompletionStage<QqMessageModels.UrlLink> generateUrlLink(String callbackData) {
        if (callbackData != null && callbackData.codePointCount(0, callbackData.length()) > 32) {
            throw new IllegalArgumentException("callbackData must not exceed 32 characters");
        }
        return authorizedPost(endpoint("v2/generate_url_link"),
                new QqMessageModels.UrlLinkRequest(normalizeOptional(callbackData)),
                QqMessageModels.UrlLink.class);
    }

    public CompletionStage<List<QqGuildModels.Guild>> getCurrentGuilds() {
        return getCurrentGuilds(null, null, null);
    }

    public CompletionStage<List<QqGuildModels.Guild>> getCurrentGuilds(
            String before, String after, Integer limit) {
        if (before != null && after != null) {
            throw new IllegalArgumentException("before and after cannot both be set");
        }
        validateOptionalLimit(limit, 1, 100, "limit");
        Map<String, Object> query = new LinkedHashMap<>();
        putOptional(query, "before", before);
        putOptional(query, "after", after);
        putOptional(query, "limit", limit);
        return authorizedGet(withQuery(endpoint("users/@me/guilds"), query),
                QqGuildModels.Guild[].class).thenApply(QqOpenApiClient::immutableList);
    }

    public CompletionStage<QqGuildModels.Guild> getGuild(String guildId) {
        return authorizedGet(endpoint("guilds/" + pathSegment(guildId, "guildId")),
                QqGuildModels.Guild.class);
    }

    public CompletionStage<List<QqGuildModels.Channel>> getChannels(String guildId) {
        return authorizedGet(endpoint("guilds/" + pathSegment(guildId, "guildId") + "/channels"),
                QqGuildModels.Channel[].class).thenApply(QqOpenApiClient::immutableList);
    }

    public CompletionStage<QqGuildModels.Channel> getChannel(String channelId) {
        return authorizedGet(endpoint("channels/" + pathSegment(channelId, "channelId")),
                QqGuildModels.Channel.class);
    }

    public CompletionStage<QqGuildModels.Channel> createChannel(
            String guildId, QqGuildModels.ChannelUpdate request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(endpoint("guilds/" + pathSegment(guildId, "guildId") + "/channels"),
                request, QqGuildModels.Channel.class);
    }

    public CompletionStage<QqGuildModels.Channel> updateChannel(
            String channelId, QqGuildModels.ChannelUpdate request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPatch(endpoint("channels/" + pathSegment(channelId, "channelId")),
                request, QqGuildModels.Channel.class);
    }

    public CompletionStage<Void> deleteChannel(String channelId) {
        return authorizedDelete(endpoint("channels/" + pathSegment(channelId, "channelId")),
                Void.class);
    }

    public CompletionStage<List<QqGuildModels.Member>> getGuildMembers(
            String guildId, String after, Integer limit) {
        validateOptionalLimit(limit, 1, 1000, "limit");
        Map<String, Object> query = new LinkedHashMap<>();
        putOptional(query, "after", after);
        putOptional(query, "limit", limit);
        URI endpoint = endpoint("guilds/" + pathSegment(guildId, "guildId") + "/members");
        return authorizedGet(withQuery(endpoint, query), QqGuildModels.Member[].class)
                .thenApply(QqOpenApiClient::immutableList);
    }

    public CompletionStage<QqGuildModels.Member> getGuildMember(
            String guildId, String userId) {
        return authorizedGet(guildMemberEndpoint(guildId, userId), QqGuildModels.Member.class);
    }

    public CompletionStage<Void> removeGuildMember(
            String guildId, String userId, QqGuildModels.MemberDeleteRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedDelete(guildMemberEndpoint(guildId, userId), request, Void.class);
    }

    public CompletionStage<QqGuildModels.RoleMembersPage> getRoleMembers(
            String guildId, String roleId, String startIndex, Integer limit) {
        validateOptionalLimit(limit, 1, 400, "limit");
        Map<String, Object> query = new LinkedHashMap<>();
        putOptional(query, "start_index", startIndex);
        putOptional(query, "limit", limit);
        URI endpoint = endpoint("guilds/" + pathSegment(guildId, "guildId") + "/roles/"
                + pathSegment(roleId, "roleId") + "/members");
        return authorizedGet(withQuery(endpoint, query), QqGuildModels.RoleMembersPage.class);
    }

    public CompletionStage<QqGuildModels.GuildRoles> getRoles(String guildId) {
        return authorizedGet(rolesEndpoint(guildId), QqGuildModels.GuildRoles.class);
    }

    public CompletionStage<QqGuildModels.RoleUpdateResult> createRole(
            String guildId, QqGuildModels.RoleUpdate request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(rolesEndpoint(guildId), request,
                QqGuildModels.RoleUpdateResult.class);
    }

    public CompletionStage<QqGuildModels.RoleUpdateResult> updateRole(
            String guildId, String roleId, QqGuildModels.RoleUpdate request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPatch(roleEndpoint(guildId, roleId), request,
                QqGuildModels.RoleUpdateResult.class);
    }

    public CompletionStage<Void> deleteRole(String guildId, String roleId) {
        return authorizedDelete(roleEndpoint(guildId, roleId), Void.class);
    }

    public CompletionStage<Void> addGuildMemberRole(
            String guildId,
            String userId,
            String roleId,
            QqGuildModels.MemberRoleRequest request) {
        Object body = request == null ? Map.of() : request;
        return authorizedPut(memberRoleEndpoint(guildId, userId, roleId), body, Void.class);
    }

    public CompletionStage<Void> removeGuildMemberRole(
            String guildId,
            String userId,
            String roleId,
            QqGuildModels.MemberRoleRequest request) {
        Object body = request == null ? Map.of() : request;
        return authorizedDelete(memberRoleEndpoint(guildId, userId, roleId), body, Void.class);
    }

    public CompletionStage<QqPermissionModels.ApiPermissions> getGuildApiPermissions(
            String guildId) {
        return authorizedGet(endpoint("guilds/" + pathSegment(guildId, "guildId")
                + "/api_permission"), QqPermissionModels.ApiPermissions.class);
    }

    public CompletionStage<QqPermissionModels.ApiPermissionDemand> demandGuildApiPermission(
            String guildId, QqPermissionModels.ApiPermissionDemandRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(endpoint("guilds/" + pathSegment(guildId, "guildId")
                        + "/api_permission/demand"), request,
                QqPermissionModels.ApiPermissionDemand.class);
    }

    public CompletionStage<QqPermissionModels.ChannelPermission> getChannelMemberPermission(
            String channelId, String userId) {
        return authorizedGet(channelMemberPermissionEndpoint(channelId, userId),
                QqPermissionModels.ChannelPermission.class);
    }

    public CompletionStage<Void> updateChannelMemberPermission(
            String channelId,
            String userId,
            QqPermissionModels.ChannelPermissionUpdate request) {
        validatePermissionUpdate(request);
        return authorizedPut(channelMemberPermissionEndpoint(channelId, userId), request, Void.class);
    }

    public CompletionStage<QqPermissionModels.ChannelPermission> getChannelRolePermission(
            String channelId, String roleId) {
        return authorizedGet(channelRolePermissionEndpoint(channelId, roleId),
                QqPermissionModels.ChannelPermission.class);
    }

    public CompletionStage<Void> updateChannelRolePermission(
            String channelId,
            String roleId,
            QqPermissionModels.ChannelPermissionUpdate request) {
        validatePermissionUpdate(request);
        return authorizedPut(channelRolePermissionEndpoint(channelId, roleId), request, Void.class);
    }

    public CompletionStage<Void> muteGuild(
            String guildId, QqGuildModels.MuteRequest request) {
        validateMuteRequest(request, false);
        return authorizedPatch(guildMuteEndpoint(guildId), request, Void.class);
    }

    public CompletionStage<QqGuildModels.MuteResult> muteGuildMembers(
            String guildId, QqGuildModels.MuteRequest request) {
        validateMuteRequest(request, true);
        return authorizedPatch(guildMuteEndpoint(guildId), request, QqGuildModels.MuteResult.class);
    }

    public CompletionStage<Void> muteGuildMember(
            String guildId, String userId, QqGuildModels.MuteRequest request) {
        validateMuteRequest(request, false);
        URI endpoint = URI.create(guildMemberEndpoint(guildId, userId).toString() + "/mute");
        return authorizedPatch(endpoint, request, Void.class);
    }

    public CompletionStage<QqGuildModels.MessageSetting> getGuildMessageSetting(String guildId) {
        return authorizedGet(endpoint("guilds/" + pathSegment(guildId, "guildId")
                + "/message/setting"), QqGuildModels.MessageSetting.class);
    }

    public CompletionStage<QqGuildModels.OnlineMemberCount> getChannelOnlineMemberCount(
            String channelId) {
        return authorizedGet(endpoint("channels/" + pathSegment(channelId, "channelId")
                + "/online_nums"), QqGuildModels.OnlineMemberCount.class);
    }

    public CompletionStage<Void> addMessageReaction(
            String channelId, String messageId, int emojiType, String emojiId) {
        validateEmojiType(emojiType);
        return authorizedPut(reactionEndpoint(channelId, messageId, emojiType, emojiId),
                null, Void.class);
    }

    public CompletionStage<Void> deleteMessageReaction(
            String channelId, String messageId, int emojiType, String emojiId) {
        validateEmojiType(emojiType);
        return authorizedDelete(reactionEndpoint(channelId, messageId, emojiType, emojiId),
                Void.class);
    }

    public CompletionStage<QqContentModels.ReactionUsers> getMessageReactionUsers(
            String channelId,
            String messageId,
            int emojiType,
            String emojiId,
            String cookie,
            Integer limit) {
        validateEmojiType(emojiType);
        validateOptionalLimit(limit, 1, 100, "limit");
        Map<String, Object> query = new LinkedHashMap<>();
        putOptional(query, "cookie", cookie);
        putOptional(query, "limit", limit);
        return authorizedGet(withQuery(
                        reactionEndpoint(channelId, messageId, emojiType, emojiId), query),
                QqContentModels.ReactionUsers.class);
    }

    public CompletionStage<QqContentModels.Announcement> createGuildAnnouncement(
            String guildId, QqContentModels.GuildAnnouncementRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(guildAnnouncementsEndpoint(guildId), request,
                QqContentModels.Announcement.class);
    }

    public CompletionStage<Void> deleteGuildAnnouncement(String guildId, String messageId) {
        return authorizedDelete(guildAnnouncementsEndpoint(guildId).resolve(
                "announces/" + pathSegment(messageId, "messageId")), Void.class);
    }

    public CompletionStage<Void> clearGuildAnnouncements(String guildId) {
        return deleteGuildAnnouncement(guildId, "all");
    }

    public CompletionStage<QqContentModels.Pins> getPins(String channelId) {
        return authorizedGet(pinsEndpoint(channelId), QqContentModels.Pins.class);
    }

    public CompletionStage<QqContentModels.Pins> addPin(String channelId, String messageId) {
        return authorizedPut(pinEndpoint(channelId, messageId), Map.of(),
                QqContentModels.Pins.class);
    }

    public CompletionStage<Void> deletePin(String channelId, String messageId) {
        return authorizedDelete(pinEndpoint(channelId, messageId), Void.class);
    }

    public CompletionStage<Void> clearPins(String channelId) {
        return deletePin(channelId, "all");
    }

    public CompletionStage<List<QqContentModels.Schedule>> getSchedules(
            String channelId, Long since) {
        URI endpoint = schedulesEndpoint(channelId);
        if (since != null) {
            if (since < 0L) {
                throw new IllegalArgumentException("since must not be negative");
            }
            endpoint = withQuery(endpoint, Map.of("since", since));
        }
        return authorizedGet(endpoint, QqContentModels.Schedule[].class)
                .thenApply(QqOpenApiClient::immutableList);
    }

    public CompletionStage<QqContentModels.Schedule> getSchedule(
            String channelId, String scheduleId) {
        return authorizedGet(scheduleEndpoint(channelId, scheduleId),
                QqContentModels.Schedule.class);
    }

    public CompletionStage<QqContentModels.Schedule> createSchedule(
            String channelId, QqContentModels.ScheduleInput request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPost(schedulesEndpoint(channelId),
                new QqContentModels.ScheduleRequest(request), QqContentModels.Schedule.class);
    }

    public CompletionStage<QqContentModels.Schedule> updateSchedule(
            String channelId, String scheduleId, QqContentModels.ScheduleInput request) {
        Objects.requireNonNull(request, "request must not be null");
        return authorizedPatch(scheduleEndpoint(channelId, scheduleId),
                new QqContentModels.ScheduleRequest(request), QqContentModels.Schedule.class);
    }

    public CompletionStage<Void> deleteSchedule(String channelId, String scheduleId) {
        return authorizedDelete(scheduleEndpoint(channelId, scheduleId), Void.class);
    }

    public CompletionStage<QqContentModels.ForumThreads> getForumThreads(String channelId) {
        return authorizedGet(threadsEndpoint(channelId), QqContentModels.ForumThreads.class);
    }

    public CompletionStage<QqContentModels.ForumThread> getForumThread(
            String channelId, String threadId) {
        return authorizedGet(threadEndpoint(channelId, threadId),
                QqContentModels.ForumThread.class);
    }

    public CompletionStage<QqContentModels.ForumThreadCreateResult> createForumThread(
            String channelId, QqContentModels.ForumThreadRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.format() == null || request.format() < 1 || request.format() > 4) {
            throw new IllegalArgumentException("format must be between 1 and 4");
        }
        return authorizedPut(threadsEndpoint(channelId), request,
                QqContentModels.ForumThreadCreateResult.class);
    }

    public CompletionStage<Void> deleteForumThread(String channelId, String threadId) {
        return authorizedDelete(threadEndpoint(channelId, threadId), Void.class);
    }

    public CompletionStage<Void> controlAudio(
            String channelId, QqContentModels.AudioControl request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.status() == null || request.status() < 0 || request.status() > 3) {
            throw new IllegalArgumentException("audio status must be between 0 and 3");
        }
        return authorizedPost(endpoint("channels/" + pathSegment(channelId, "channelId")
                + "/audio"), request, Void.class);
    }

    public CompletionStage<Void> putBotOnMic(String channelId) {
        return authorizedPut(micEndpoint(channelId), null, Void.class);
    }

    public CompletionStage<Void> removeBotFromMic(String channelId) {
        return authorizedDelete(micEndpoint(channelId), Void.class);
    }

    public CompletionStage<QqMessageSendResult> sendText(QqTextMessageRequest request) {
        return sendText(request, QqMessageSendOptions.none());
    }

    public CompletionStage<QqMessageSendResult> sendText(
            QqTextMessageRequest request, QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(request, "request must not be null");
        validateMessageOptions(request.targetType(), request.replyMessageId().orElse(null),
                request.replyEventId().orElse(null), sendOptions);
        URI endpoint = messageEndpoint(request.targetType(), request.targetId());
        TextMessagePayload body = new TextMessagePayload(
                request.content(), request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                request.messageSequence(), request.targetType() == QqMessageTargetType.CHANNEL
                        || request.targetType() == QqMessageTargetType.DIRECT ? null : 0,
                sendOptions.messageReference().orElse(null), sendOptions.wakeup() ? Boolean.TRUE : null);
        return tokenProvider.getAccessToken()
                .thenCompose(token -> transport.postAuthorizedJson(
                        endpoint, token, body, applicationHeaders, QqMessageSendResult.class));
    }

    public CompletionStage<QqMessageSendResult> sendMedia(QqMediaMessageRequest request) {
        return sendMedia(request, QqMessageSendOptions.none());
    }

    public CompletionStage<QqMessageSendResult> sendMedia(
            QqMediaMessageRequest request, QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(request, "request must not be null");
        validateMessageOptions(request.targetType(), request.replyMessageId().orElse(null),
                request.replyEventId().orElse(null), sendOptions);
        if (request.targetType() == QqMessageTargetType.C2C
                || request.targetType() == QqMessageTargetType.GROUP) {
            URI uploadEndpoint = mediaUploadEndpoint(request.targetType(), request.targetId());
            MediaUploadPayload upload = new MediaUploadPayload(
                    request.mediaKind().fileType(), request.mediaUrl().toString(), null, false);
            return tokenProvider.getAccessToken().thenCompose(token ->
                    transport.postAuthorizedJson(uploadEndpoint, token, upload, applicationHeaders,
                                    QqMediaUploadResult.class)
                            .thenCompose(result -> {
                                if (result.fileInfo() == null || result.fileInfo().isBlank()) {
                                    return java.util.concurrent.CompletableFuture.failedFuture(
                                            QqClientException.protocol(uploadEndpoint,
                                                    new IllegalArgumentException("file_info is missing")));
                                }
                                MediaMessagePayload message = new MediaMessagePayload(
                                        request.content().orElse(null), new MediaReference(result.fileInfo()),
                                        request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                                        request.messageSequence(), 7,
                                        sendOptions.messageReference().orElse(null),
                                        sendOptions.wakeup() ? Boolean.TRUE : null);
                                return transport.postAuthorizedJson(
                                        messageEndpoint(request.targetType(), request.targetId()), token,
                                        message, applicationHeaders, QqMessageSendResult.class);
                            }));
        }
        if (request.mediaKind() != QqMediaKind.IMAGE) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Channel and direct messages only support image URLs"));
        }
        ChannelImagePayload payload = new ChannelImagePayload(
                request.content().orElse(null), request.mediaUrl().toString(),
                request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                sendOptions.messageReference().orElse(null));
        URI endpoint = messageEndpoint(request.targetType(), request.targetId());
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.postAuthorizedJson(
                        endpoint, token, payload, applicationHeaders, QqMessageSendResult.class));
    }

    /** Downloads a remote URL under the configured SSRF, redirect, timeout, and size policy. */
    public CompletionStage<QqMessageSendResult> sendMediaBounded(QqMediaMessageRequest request) {
        return sendMediaBounded(request, QqMessageSendOptions.none());
    }

    public CompletionStage<QqMessageSendResult> sendMediaBounded(
            QqMediaMessageRequest request, QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(request, "request must not be null");
        if ((request.targetType() == QqMessageTargetType.CHANNEL
                || request.targetType() == QqMessageTargetType.DIRECT)
                && request.mediaKind() != QqMediaKind.IMAGE) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Channel and direct messages only support image media"));
        }
        return mediaDownloader.download(request.mediaUrl())
                .thenCompose(bytes -> sendMedia(request, bytes, sendOptions));
    }

    /** Sends caller-owned media bytes using QQ's file_data upload form. */
    public CompletionStage<QqMessageSendResult> sendMedia(QqMediaMessageRequest request, byte[] mediaBytes) {
        return sendMedia(request, mediaBytes, QqMessageSendOptions.none());
    }

    public CompletionStage<QqMessageSendResult> sendMedia(
            QqMediaMessageRequest request, byte[] mediaBytes, QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(mediaBytes, "mediaBytes must not be null");
        validateMessageOptions(request.targetType(), request.replyMessageId().orElse(null),
                request.replyEventId().orElse(null), sendOptions);
        if (mediaBytes.length == 0) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("media bytes must not be empty"));
        }
        if (mediaBytes.length > options.maxMediaBytes()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("media bytes exceed configured limit"));
        }
        if (request.targetType() == QqMessageTargetType.CHANNEL
                || request.targetType() == QqMessageTargetType.DIRECT) {
            if (request.mediaKind() != QqMediaKind.IMAGE) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                        new IllegalArgumentException("Channel and direct messages only support image media"));
            }
            Map<String, String> fields = new HashMap<>();
            request.content().ifPresent(value -> fields.put("content", value));
            request.replyMessageId().ifPresent(value -> fields.put("msg_id", value));
            request.replyEventId().ifPresent(value -> fields.put("event_id", value));
            sendOptions.messageReference().ifPresent(value ->
                    fields.put("message_reference", jsonCodec.encode(value)));
            fields.put("msg_seq", Integer.toString(request.messageSequence()));
            URI endpoint = messageEndpoint(request.targetType(), request.targetId());
            return tokenProvider.getAccessToken().thenCompose(token ->
                    transport.postAuthorizedMultipart(endpoint, token, applicationHeaders, fields, "file_image",
                            "qqbot-image.bin", "application/octet-stream", mediaBytes,
                            QqMessageSendResult.class));
        }
        if (request.targetType() != QqMessageTargetType.C2C
                && request.targetType() != QqMessageTargetType.GROUP) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unsupported media target"));
        }
        URI uploadEndpoint = mediaUploadEndpoint(request.targetType(), request.targetId());
        MediaUploadPayload upload = new MediaUploadPayload(
                request.mediaKind().fileType(), null,
                Base64.getEncoder().encodeToString(mediaBytes), false);
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.postAuthorizedJson(uploadEndpoint, token, upload, applicationHeaders,
                                QqMediaUploadResult.class)
                        .thenCompose(result -> sendUploadedMedia(
                                request, sendOptions, token, uploadEndpoint, result)));
    }

    public CompletionStage<QqMessageSendResult> sendMarkdown(QqMarkdownMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> markdown = new HashMap<>();
        markdown.put("content", request.content());
        request.customTemplateId().ifPresent(value -> markdown.put("custom_template_id", value));
        if (!request.params().isEmpty()) {
            markdown.put("params", request.params().entrySet().stream()
                    .map(entry -> Map.of("key", entry.getKey(), "values", List.of(entry.getValue())))
                    .toList());
        }
        return sendRich(new QqRichMessageRequest(request.targetType(), request.targetId(),
                QqRichMessageKind.MARKDOWN, markdown, request.replyMessageId(), request.replyEventId(),
                request.messageSequence()));
    }

    public CompletionStage<QqMessageSendResult> sendKeyboard(QqKeyboardMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> keyboard = new HashMap<>();
        request.keyboardId().ifPresent(value -> keyboard.put("id", value));
        if (!request.rows().isEmpty()) keyboard.put("content", Map.of("rows", request.rows()));
        Map<String, Object> composite = Map.of(
                "markdown", Map.of("content", request.markdownContent()),
                "keyboard", keyboard);
        return sendRich(new QqRichMessageRequest(request.targetType(), request.targetId(),
                QqRichMessageKind.KEYBOARD, composite, request.replyMessageId(), request.replyEventId(),
                request.messageSequence()));
    }

    public CompletionStage<QqMessageSendResult> sendArk(QqArkMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> ark = Map.of("template_id", request.templateId(), "kv", request.values());
        return sendRich(new QqRichMessageRequest(request.targetType(), request.targetId(),
                QqRichMessageKind.ARK, ark, request.replyMessageId(), request.replyEventId(),
                request.messageSequence()));
    }

    public CompletionStage<QqMessageSendResult> sendEmbed(QqEmbedMessageRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Map<String, Object> embed = new HashMap<>();
        embed.put("title", request.title());
        if (request.prompt() != null) embed.put("prompt", request.prompt());
        if (!request.fields().isEmpty()) embed.put("fields", request.fields());
        return sendRich(new QqRichMessageRequest(request.targetType(), request.targetId(),
                QqRichMessageKind.EMBED, embed, request.replyMessageId(), request.replyEventId(),
                request.messageSequence()));
    }

    public CompletionStage<QqMessageSendResult> sendRich(QqRichMessageRequest request) {
        return sendRich(request, QqMessageSendOptions.none());
    }

    public CompletionStage<QqMessageSendResult> sendRich(
            QqRichMessageRequest request, QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(request, "request must not be null");
        validateMessageOptions(request.targetType(), request.replyMessageId().orElse(null),
                request.replyEventId().orElse(null), sendOptions);
        String key = request.kind().name().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> body = new HashMap<>();
        if (request.kind() == QqRichMessageKind.KEYBOARD) body.putAll(request.payload());
        else body.put(key, request.payload());
        body.put("msg_id", request.replyMessageId().orElse(null));
        body.put("event_id", request.replyEventId().orElse(null));
        body.put("msg_seq", request.messageSequence());
        body.put("message_reference", sendOptions.messageReference().orElse(null));
        body.put("is_wakeup", sendOptions.wakeup() ? Boolean.TRUE : null);
        if (request.targetType() == QqMessageTargetType.C2C
                || request.targetType() == QqMessageTargetType.GROUP) {
            body.put("msg_type", switch (request.kind()) {
                case MARKDOWN -> 2;
                case ARK -> 3;
                case EMBED -> 4;
                case KEYBOARD -> 2;
            });
        }
        URI endpoint = messageEndpoint(request.targetType(), request.targetId());
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.postAuthorizedJson(
                        endpoint, token, body, applicationHeaders, QqMessageSendResult.class));
    }

    private CompletionStage<QqMessageSendResult> sendUploadedMedia(
            QqMediaMessageRequest request, QqMessageSendOptions sendOptions,
            AccessToken token, URI uploadEndpoint,
            QqMediaUploadResult result) {
        if (result.fileInfo() == null || result.fileInfo().isBlank()) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    QqClientException.protocol(uploadEndpoint,
                            new IllegalArgumentException("file_info is missing")));
        }
        MediaMessagePayload message = new MediaMessagePayload(
                request.content().orElse(null), new MediaReference(result.fileInfo()),
                request.replyMessageId().orElse(null), request.replyEventId().orElse(null),
                request.messageSequence(), 7, sendOptions.messageReference().orElse(null),
                sendOptions.wakeup() ? Boolean.TRUE : null);
        return transport.postAuthorizedJson(messageEndpoint(request.targetType(), request.targetId()), token,
                message, applicationHeaders, QqMessageSendResult.class);
    }

    private <T> CompletionStage<T> authorizedGet(URI endpoint, Class<T> responseType) {
        return tokenProvider
                .getAccessToken()
                .thenCompose(token -> transport.getJson(
                        endpoint, token, applicationHeaders, responseType));
    }

    private <T> CompletionStage<T> authorizedPost(
            URI endpoint, Object body, Class<T> responseType) {
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.postAuthorizedJson(
                        endpoint, token, body, applicationHeaders, responseType));
    }

    private <T> CompletionStage<T> authorizedPut(
            URI endpoint, Object body, Class<T> responseType) {
        return authorizedPut(endpoint, body, applicationHeaders, responseType);
    }

    private <T> CompletionStage<T> authorizedPut(
            URI endpoint,
            Object body,
            Map<String, String> headers,
            Class<T> responseType) {
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.putAuthorizedJson(endpoint, token, body, headers, responseType));
    }

    private <T> CompletionStage<T> authorizedPatch(
            URI endpoint, Object body, Class<T> responseType) {
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.patchAuthorizedJson(
                        endpoint, token, body, applicationHeaders, responseType));
    }

    private <T> CompletionStage<T> authorizedDelete(URI endpoint, Class<T> responseType) {
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.deleteAuthorized(endpoint, token, applicationHeaders, responseType));
    }

    private <T> CompletionStage<T> authorizedDelete(
            URI endpoint, Object body, Class<T> responseType) {
        return tokenProvider.getAccessToken().thenCompose(token ->
                transport.deleteAuthorizedJson(
                        endpoint, token, body, applicationHeaders, responseType));
    }

    private GatewayBotInfo toGatewayBotInfo(GatewayBotProtocolResponse response, URI endpoint) {
        try {
            SessionStartLimitProtocolResponse limit = Objects.requireNonNull(
                    response.session_start_limit(), "session_start_limit must not be null");
            return new GatewayBotInfo(
                    parseGatewayUri(response.url(), endpoint),
                    response.shards(),
                    new SessionStartLimit(
                            limit.total(),
                            limit.remaining(),
                            Duration.ofMillis(limit.reset_after()),
                            limit.max_concurrency()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private BotProfile toBotProfile(CurrentBotUser response, URI endpoint) {
        try {
            if (response.avatar() == null || response.avatar().isBlank()) {
                return new BotProfile(response.id(), response.username());
            }
            return new BotProfile(response.id(), response.username(), URI.create(response.avatar()));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private URI parseGatewayUri(String value, URI endpoint) {
        try {
            URI uri = URI.create(Objects.requireNonNull(value, "url must not be null"));
            // Reuse the stable model's WSS validation without exposing transport DTOs.
            new GatewayBotInfo(uri, 1, new SessionStartLimit(1, 1, Duration.ZERO, 1));
            return uri;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw QqClientException.protocol(endpoint, exception);
        }
    }

    private URI endpoint(String path) {
        return options.openApiBaseUri().resolve(path);
    }

    private URI messageEndpoint(QqMessageTargetType type, String id) {
        String encoded = pathSegment(id, "targetId");
        return switch (type) {
            case C2C -> endpoint("v2/users/" + encoded + "/messages");
            case GROUP -> endpoint("v2/groups/" + encoded + "/messages");
            case CHANNEL -> endpoint("channels/" + encoded + "/messages");
            case DIRECT -> endpoint("dms/" + encoded + "/messages");
        };
    }

    private URI messageResourceEndpoint(
            QqMessageTargetType type, String targetId, String messageId) {
        return URI.create(messageEndpoint(type, targetId).toString()
                + "/" + pathSegment(messageId, "messageId"));
    }

    private URI mediaUploadEndpoint(QqMessageTargetType type, String id) {
        String encoded = pathSegment(id, "targetId");
        return switch (type) {
            case C2C -> endpoint("v2/users/" + encoded + "/files");
            case GROUP -> endpoint("v2/groups/" + encoded + "/files");
            case CHANNEL, DIRECT -> throw new IllegalArgumentException("This target does not use media pre-upload");
        };
    }

    private URI guildMemberEndpoint(String guildId, String userId) {
        return endpoint("guilds/" + pathSegment(guildId, "guildId") + "/members/"
                + pathSegment(userId, "userId"));
    }

    private URI rolesEndpoint(String guildId) {
        return endpoint("guilds/" + pathSegment(guildId, "guildId") + "/roles");
    }

    private URI roleEndpoint(String guildId, String roleId) {
        return URI.create(rolesEndpoint(guildId).toString() + "/" + pathSegment(roleId, "roleId"));
    }

    private URI memberRoleEndpoint(String guildId, String userId, String roleId) {
        return URI.create(guildMemberEndpoint(guildId, userId).toString() + "/roles/"
                + pathSegment(roleId, "roleId"));
    }

    private URI channelMemberPermissionEndpoint(String channelId, String userId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/members/"
                + pathSegment(userId, "userId") + "/permissions");
    }

    private URI channelRolePermissionEndpoint(String channelId, String roleId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/roles/"
                + pathSegment(roleId, "roleId") + "/permissions");
    }

    private URI guildMuteEndpoint(String guildId) {
        return endpoint("guilds/" + pathSegment(guildId, "guildId") + "/mute");
    }

    private URI reactionEndpoint(
            String channelId, String messageId, int emojiType, String emojiId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/messages/"
                + pathSegment(messageId, "messageId") + "/reactions/" + emojiType + "/"
                + pathSegment(emojiId, "emojiId"));
    }

    private URI guildAnnouncementsEndpoint(String guildId) {
        return endpoint("guilds/" + pathSegment(guildId, "guildId") + "/announces");
    }

    private URI pinsEndpoint(String channelId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/pins");
    }

    private URI pinEndpoint(String channelId, String messageId) {
        return URI.create(pinsEndpoint(channelId).toString() + "/"
                + pathSegment(messageId, "messageId"));
    }

    private URI schedulesEndpoint(String channelId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/schedules");
    }

    private URI scheduleEndpoint(String channelId, String scheduleId) {
        return URI.create(schedulesEndpoint(channelId).toString() + "/"
                + pathSegment(scheduleId, "scheduleId"));
    }

    private URI threadsEndpoint(String channelId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/threads");
    }

    private URI threadEndpoint(String channelId, String threadId) {
        return URI.create(threadsEndpoint(channelId).toString() + "/"
                + pathSegment(threadId, "threadId"));
    }

    private URI micEndpoint(String channelId) {
        return endpoint("channels/" + pathSegment(channelId, "channelId") + "/mic");
    }

    private static URI withQuery(URI endpoint, Map<String, ?> values) {
        if (values.isEmpty()) {
            return endpoint;
        }
        StringJoiner query = new StringJoiner("&");
        values.forEach((name, value) -> {
            if (value != null && (!(value instanceof String string) || !string.isBlank())) {
                query.add(encodeQuery(name) + "=" + encodeQuery(String.valueOf(value)));
            }
        });
        return query.length() == 0
                ? endpoint
                : URI.create(endpoint + (endpoint.getQuery() == null ? "?" : "&") + query);
    }

    private static String pathSegment(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isWhitespace)
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return encodeQuery(value);
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void putOptional(Map<String, Object> target, String name, Object value) {
        if (value instanceof String string) {
            String normalized = normalizeOptional(string);
            if (normalized != null) {
                target.put(name, normalized);
            }
        } else if (value != null) {
            target.put(name, value);
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void validateOptionalLimit(
            Integer value, int minimum, int maximum, String name) {
        if (value != null && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void validateEmojiType(int emojiType) {
        if (emojiType != 1 && emojiType != 2) {
            throw new IllegalArgumentException("emojiType must be 1 or 2");
        }
    }

    private static void validatePermissionUpdate(
            QqPermissionModels.ChannelPermissionUpdate request) {
        Objects.requireNonNull(request, "request must not be null");
        validateUnsignedPermission(request.add(), "add");
        validateUnsignedPermission(request.remove(), "remove");
        if (normalizeOptional(request.add()) == null && normalizeOptional(request.remove()) == null) {
            throw new IllegalArgumentException("add or remove must be set");
        }
    }

    private static void validateUnsignedPermission(String value, String name) {
        if (normalizeOptional(value) == null) {
            return;
        }
        try {
            Long.parseUnsignedLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an unsigned integer", exception);
        }
    }

    private static void validateMuteRequest(
            QqGuildModels.MuteRequest request, boolean requireUsers) {
        Objects.requireNonNull(request, "request must not be null");
        if (normalizeOptional(request.muteEndTimestamp()) == null
                && normalizeOptional(request.muteSeconds()) == null) {
            throw new IllegalArgumentException("muteEndTimestamp or muteSeconds must be set");
        }
        if (requireUsers && (request.userIds() == null || request.userIds().isEmpty())) {
            throw new IllegalArgumentException("userIds must not be empty for a batch mute");
        }
    }

    private static void validateMessageOptions(
            QqMessageTargetType targetType,
            String replyMessageId,
            String replyEventId,
            QqMessageSendOptions sendOptions) {
        Objects.requireNonNull(sendOptions, "sendOptions must not be null");
        if (!sendOptions.wakeup()) {
            return;
        }
        if (targetType != QqMessageTargetType.C2C) {
            throw new IllegalArgumentException("is_wakeup is only supported for C2C messages");
        }
        if (replyMessageId != null || replyEventId != null) {
            throw new IllegalArgumentException(
                    "is_wakeup cannot be combined with msg_id or event_id");
        }
    }

    private static <T> List<T> immutableList(T[] values) {
        Objects.requireNonNull(values, "QQ response array must not be null");
        return List.copyOf(Arrays.asList(values));
    }

    private record TextMessagePayload(
            String content,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_seq") Integer messageSequence,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_type") Integer messageType,
            @com.fasterxml.jackson.annotation.JsonProperty("message_reference")
                    QqMessageModels.MessageReference messageReference,
            @com.fasterxml.jackson.annotation.JsonProperty("is_wakeup") Boolean wakeup) {}

    private record MediaUploadPayload(
            @com.fasterxml.jackson.annotation.JsonProperty("file_type") int fileType,
            String url,
            @com.fasterxml.jackson.annotation.JsonProperty("file_data") String fileData,
            @com.fasterxml.jackson.annotation.JsonProperty("srv_send_msg") boolean serverSendMessage) {}

    private record MediaReference(
            @com.fasterxml.jackson.annotation.JsonProperty("file_info") String fileInfo) {}

    private record MediaMessagePayload(
            String content,
            MediaReference media,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_seq") int messageSequence,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_type") int messageType,
            @com.fasterxml.jackson.annotation.JsonProperty("message_reference")
                    QqMessageModels.MessageReference messageReference,
            @com.fasterxml.jackson.annotation.JsonProperty("is_wakeup") Boolean wakeup) {}

    private record ChannelImagePayload(
            String content,
            String image,
            @com.fasterxml.jackson.annotation.JsonProperty("msg_id") String messageId,
            @com.fasterxml.jackson.annotation.JsonProperty("event_id") String eventId,
            @com.fasterxml.jackson.annotation.JsonProperty("message_reference")
                    QqMessageModels.MessageReference messageReference) {}
}
