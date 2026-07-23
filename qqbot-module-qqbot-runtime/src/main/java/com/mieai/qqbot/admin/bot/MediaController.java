package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.client.FileMediaAssetStore;
import com.mieai.qqbot.client.MediaAsset;
import com.mieai.qqbot.client.MediaAssetStore;
import com.mieai.qqbot.client.QqMediaKind;
import com.mieai.qqbot.domain.bot.BotId;
import com.mieai.qqbot.persistence.bot.BotRepository;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/bots")
public class MediaController {
    private final BotRepository bots;
    private final MediaAssetStore mediaStore;
    private final BotMessageAdministrationService messages;

    public MediaController(BotRepository bots, MediaAssetStore mediaStore,
            BotMessageAdministrationService messages) {
        this.bots = Objects.requireNonNull(bots, "bots must not be null");
        this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    @PostMapping(path = "/{botId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaUploadResponse> upload(@PathVariable String botId,
            @RequestParam QqMediaKind kind, @RequestPart("file") MultipartFile file) {
        BotId id = BotId.parse(botId);
        var bot = bots.findById(id).orElseThrow(() -> new BotMessageAdministrationException(
                HttpStatus.NOT_FOUND, "BOT_NOT_FOUND", "Bot does not exist"));
        long max = bot.definition().maxMediaUploadBytes();
        if (file.getSize() > max) throw new MediaUploadTooLargeException(file.getSize(), max);
        try {
            MediaAsset asset = mediaStore.stage(id, kind, file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                    file.getInputStream(), max);
            return ResponseEntity.status(HttpStatus.CREATED).body(MediaUploadResponse.from(asset));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read uploaded media", exception);
        } catch (FileMediaAssetStore.MediaSizeExceededException exception) {
            throw new MediaUploadTooLargeException(file.getSize(), exception.maxBytes());
        }
    }

    @PostMapping(path = "/{botId}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BotMessageResponse> send(@PathVariable String botId,
            @Valid @RequestBody SendBotMessageRequest request) {
        return ResponseEntity.accepted().body(messages.send(botId, request));
    }
}
