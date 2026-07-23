package com.mieai.qqbot.admin.bot;

import com.mieai.qqbot.runtime.supervisor.BotSupervisor;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.io.IOException;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bots/runtime")
public class BotRuntimeController {

    private final BotSupervisor supervisor;
    private final ScheduledExecutorService streamExecutor = Executors.newScheduledThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "qqbot-runtime-sse");
        thread.setDaemon(true);
        return thread;
    });

    public BotRuntimeController(BotSupervisor supervisor) {
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor must not be null");
    }

    @GetMapping
    public BotRuntimeSummaryResponse runtime() {
        return BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("runtime")
                    .data(BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now())));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
            return emitter;
        }
        ScheduledFuture<?> task = streamExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("runtime")
                        .data(BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now())));
            } catch (IOException | RuntimeException exception) {
                emitter.completeWithError(exception);
            }
        }, 2, 2, TimeUnit.SECONDS);
        emitter.onCompletion(() -> task.cancel(false));
        emitter.onTimeout(() -> task.cancel(false));
        emitter.onError(ignored -> task.cancel(false));
        return emitter;
    }

    @PreDestroy
    void closeStreamExecutor() {
        streamExecutor.shutdownNow();
    }
}
