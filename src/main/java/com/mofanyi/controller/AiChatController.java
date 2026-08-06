package com.mofanyi.controller;

import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.StreamCallback;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;

    public AiChatController(AiCodeGeneratorFacade aiCodeGeneratorFacade) {
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
    }

    @GetMapping("/chat")
    public AiCodeGenResultVO chat(@RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        return aiCodeGeneratorFacade.generateHtmlProject(message);
    }

    @GetMapping("/chat/multi")
    public AiCodeGenResultVO chatMulti(@RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        return aiCodeGeneratorFacade.generateMultiFileProject(message);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateHtmlProject(message, new StreamCallback(
                token -> send(emitter, "token", token),
                vo -> {
                    send(emitter, "done", vo);
                    emitter.complete();
                },
                error -> {
                    send(emitter, "error", error.getMessage());
                    emitter.complete();
                }));
        return emitter;
    }

    @GetMapping(value = "/chat/multi/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatMultiStream(@RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateMultiFileProject(message, new StreamCallback(
                token -> send(emitter, "token", token),
                vo -> {
                    send(emitter, "done", vo);
                    emitter.complete();
                },
                error -> {
                    send(emitter, "error", error.getMessage());
                    emitter.complete();
                }));
        return emitter;
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
