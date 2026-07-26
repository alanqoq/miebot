package com.mieai.qqbot.admin.bot

import com.mieai.qqbot.runtime.supervisor.BotSupervisor
import jakarta.annotation.PreDestroy
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/bots/runtime")
class BotRuntimeController(private val supervisor: BotSupervisor) {
    private val streamExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(1) { runnable ->
            Thread(runnable, "qqbot-runtime-sse").apply { isDaemon = true }
        }

    @GetMapping
    fun runtime(): BotRuntimeSummaryResponse =
        BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now())

    @GetMapping(value = ["/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(): SseEmitter {
        val emitter = SseEmitter(0L)
        try {
            emitter.send(
                SseEmitter.event().name("runtime")
                    .data(BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now())),
            )
        } catch (exception: IOException) {
            emitter.completeWithError(exception)
            return emitter
        }
        val task = streamExecutor.scheduleAtFixedRate(
            {
                try {
                    emitter.send(
                        SseEmitter.event().name("runtime")
                            .data(BotRuntimeSummaryResponse.from(supervisor.statuses(), Instant.now())),
                    )
                } catch (exception: IOException) {
                    emitter.completeWithError(exception)
                } catch (exception: RuntimeException) {
                    emitter.completeWithError(exception)
                }
            },
            2,
            2,
            TimeUnit.SECONDS,
        )
        emitter.onCompletion { task.cancel(false) }
        emitter.onTimeout { task.cancel(false) }
        emitter.onError { task.cancel(false) }
        return emitter
    }

    @PreDestroy
    fun closeStreamExecutor() {
        streamExecutor.shutdownNow()
    }
}
