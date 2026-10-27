package com.mofanyi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mofanyi.common.PublicErrorMessage;
import com.mofanyi.entity.GenerationJob;
import com.mofanyi.entity.GenerationJobEvent;
import com.mofanyi.model.enums.GenerationJobStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JobEvent 的 SSE 只读视图，支持 Last-Event-ID/seq 重放。
 */
@Slf4j
@Service
public class GenerationJobSseService {

    private static final long SSE_TIMEOUT_MILLIS = 30 * 60_000L;
    private static final long POLL_MILLIS = 400L;
    private static final long HEARTBEAT_MILLIS = 15_000L;

    private final GenerationJobService jobService;
    private final GenerationJobEventService eventService;
    private final ExecutorService streamExecutor;

    public GenerationJobSseService(GenerationJobService jobService,
                                   GenerationJobEventService eventService,
                                   @Qualifier("jobStreamExecutor") ExecutorService streamExecutor) {
        this.jobService = jobService;
        this.eventService = eventService;
        this.streamExecutor = streamExecutor;
    }

    public SseEmitter open(Long jobId, long afterSeq, boolean legacy) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            emitter.complete();
        });
        emitter.onError(error -> closed.set(true));
        streamExecutor.execute(() -> pump(jobId, afterSeq, legacy, emitter, closed));
        return emitter;
    }

    private void pump(Long jobId, long afterSeq, boolean legacy,
                      SseEmitter emitter, AtomicBoolean closed) {
        long cursor = afterSeq;
        long lastSendAt = System.currentTimeMillis();
        try {
            while (!closed.get()) {
                List<GenerationJobEvent> events = eventService.listAfter(jobId, cursor, 200);
                for (GenerationJobEvent event : events) {
                    if ("stage".equals(event.getEventType()) && legacy) {
                        cursor = event.getSeq();
                        continue;
                    }
                    cursor = event.getSeq();
                    send(emitter, event, legacy);
                    lastSendAt = System.currentTimeMillis();
                }
                GenerationJob job = jobService.requireJob(jobId);
                if (GenerationJobStatusEnum.isTerminal(job.getStatus()) && events.isEmpty()) {
                    emitter.complete();
                    return;
                }
                if (System.currentTimeMillis() - lastSendAt >= HEARTBEAT_MILLIS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastSendAt = System.currentTimeMillis();
                }
                Thread.sleep(POLL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (Exception e) {
            if (!closed.get()) {
                log.debug("[job-sse] 连接结束，jobId={}, reason={}", jobId, e.getMessage());
                emitter.completeWithError(e);
            }
        }
    }

    private void send(SseEmitter emitter, GenerationJobEvent event, boolean legacy) throws Exception {
        JsonNode payload = publicPayload(event);
        if (!legacy) {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getSeq()))
                    .name(event.getEventType())
                    .data(payload, MediaType.APPLICATION_JSON));
            return;
        }
        String eventName;
        String data;
        switch (event.getEventType()) {
            case "progress" -> {
                eventName = "agent_progress";
                data = payload.isTextual() ? payload.asText() : payload.toString();
            }
            case "reasoning" -> {
                eventName = "reasoning";
                data = payload.isTextual() ? payload.asText() : payload.toString();
            }
            case "completed" -> {
                eventName = "done";
                data = payload.toString();
            }
            case "failed" -> {
                eventName = "error";
                data = payload.path("message").asText("生成失败");
            }
            case "cancelled" -> {
                eventName = "error";
                data = payload.path("message").asText("用户取消生成");
            }
            default -> {
                return;
            }
        }
        emitter.send(SseEmitter.event().id(String.valueOf(event.getSeq()))
                .name(eventName).data(data));
    }

    private JsonNode publicPayload(GenerationJobEvent event) {
        JsonNode payload = eventService.readPayload(event);
        if (!"failed".equals(event.getEventType())
                && !"retry_scheduled".equals(event.getEventType())) {
            return payload;
        }
        String internalType = payload.path("errorType").asText(event.getStage());
        String internalMessage = payload.path("message").asText(
                payload.isTextual() ? payload.asText() : "");
        PublicErrorMessage.Message publicError =
                PublicErrorMessage.from(internalType, internalMessage);
        if (payload instanceof ObjectNode object) {
            ObjectNode safe = object.deepCopy();
            safe.put("errorType", publicError.code());
            safe.put("message", publicError.text());
            return safe;
        }
        return TextNode.valueOf(publicError.text());
    }
}
