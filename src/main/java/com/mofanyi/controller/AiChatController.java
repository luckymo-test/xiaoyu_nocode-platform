package com.mofanyi.controller;

import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.StreamCallback;
import com.mofanyi.entity.App;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final UserService userService;
    private final AppService appService;

    public AiChatController(AiCodeGeneratorFacade aiCodeGeneratorFacade,
                            UserService userService,
                            AppService appService) {
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.userService = userService;
        this.appService = appService;
    }

    @GetMapping("/chat")
    public AiCodeGenResultVO chat(@RequestParam Long appId,
                                  @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        return aiCodeGeneratorFacade.generateHtmlProject(message, appId);
    }

    @GetMapping("/chat/multi")
    public AiCodeGenResultVO chatMulti(@RequestParam Long appId,
                                       @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        return aiCodeGeneratorFacade.generateMultiFileProject(message, appId);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam Long appId,
                                 @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateHtmlProject(message, appId, new StreamCallback(
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
    public SseEmitter chatMultiStream(@RequestParam Long appId,
                                      @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message) {
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateMultiFileProject(message, appId, new StreamCallback(
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

    /**
     * 手动停止 AI 生成（仅本人可停止）
     */
    @PostMapping("/stop")
    public BaseResponse<Boolean> stopGenerate(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        App app = appService.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        ThrowUtils.throwIf(!app.getUserId().equals(loginUser.getId()),
                ErrorCode.NO_AUTH_ERROR, "只能停止自己的生成任务");
        aiCodeGeneratorFacade.stopGenerate(appId);
        return ResultUtils.success(true);
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
