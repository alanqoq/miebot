package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.domain.bot.BotRevision;
import com.mieai.qqbot.domain.bot.GatewayIntents;
import com.mieai.qqbot.domain.bot.QqAppId;
import com.mieai.qqbot.domain.bot.ShardSpec;
import com.mieai.qqbot.runtime.configuration.BotConfigurationService;
import com.mieai.qqbot.runtime.configuration.BotConfigurationView;
import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.runtime.configuration.CreateBotCommand;
import com.mieai.qqbot.runtime.configuration.UpdateBotCommand;
import com.mieai.qqbot.runtime.security.AppSecret;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bots")
public class BotController {

    private final BotConfigurationService service;

    public BotController(BotConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public List<BotConfigurationResponse> list() {
        return service.findAll().stream().map(BotConfigurationResponse::from).toList();
    }

    @GetMapping("/{botId}")
    public ResponseEntity<BotConfigurationResponse> get(@PathVariable String botId) {
        BotConfigurationView view = service.findById(BotId.parse(botId))
                .orElseThrow(() -> new BotNotFoundException(BotId.parse(botId)));
        return response(view);
    }

    @PostMapping
    public ResponseEntity<BotConfigurationResponse> create(
            @Valid @RequestBody CreateBotRequest request) {
        BotConfigurationView view;
        try (AppSecret secret = AppSecret.of(request.appSecret())) {
            view = service.create(new CreateBotCommand(
                    request.displayName(),
                    QqAppId.of(request.appId()),
                    request.environment(),
                    GatewayIntents.of(request.intents()),
                    new ShardSpec(request.shardIndex(), request.shardCount()),
                    request.enabled(),
                    secret));
        }
        return ResponseEntity.created(URI.create("/api/bots/" + view.id()))
                .eTag(etag(view))
                .body(BotConfigurationResponse.from(view));
    }

    @PutMapping("/{botId}")
    public ResponseEntity<BotConfigurationResponse> update(
            @PathVariable String botId,
            @Valid @RequestBody UpdateBotRequest request) {
        AppSecret secret = request.appSecret() == null ? null : AppSecret.of(request.appSecret());
        BotConfigurationView view;
        try (secret) {
            view = service.update(new UpdateBotCommand(
                    BotId.parse(botId),
                    BotRevision.of(request.expectedRevision()),
                    request.displayName(),
                    QqAppId.of(request.appId()),
                    request.environment(),
                    GatewayIntents.of(request.intents()),
                    new ShardSpec(request.shardIndex(), request.shardCount()),
                    Optional.ofNullable(secret)));
        }
        return response(view);
    }

    @PatchMapping("/{botId}/enabled")
    public ResponseEntity<BotConfigurationResponse> setEnabled(
            @PathVariable String botId,
            @Valid @RequestBody SetBotEnabledRequest request) {
        BotId id = BotId.parse(botId);
        BotRevision revision = BotRevision.of(request.expectedRevision());
        BotConfigurationView view = request.enabled()
                ? service.enable(id, revision)
                : service.disable(id, revision);
        return response(view);
    }

    @DeleteMapping("/{botId}")
    public ResponseEntity<Void> delete(@PathVariable String botId) {
        service.delete(BotId.parse(botId));
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<BotConfigurationResponse> response(BotConfigurationView view) {
        return ResponseEntity.ok()
                .eTag(etag(view))
                .body(BotConfigurationResponse.from(view));
    }

    private static String etag(BotConfigurationView view) {
        return '"' + Long.toString(view.revision().value()) + '"';
    }
}
