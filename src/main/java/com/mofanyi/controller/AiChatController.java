package com.mofanyi.controller;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.common.BaseResponse;
import com.mofanyi.common.ResultUtils;
import com.mofanyi.core.AiCodeGeneratorFacade;
import com.mofanyi.core.StreamCallback;
import com.mofanyi.entity.App;
import com.mofanyi.entity.ChatHistory;
import com.mofanyi.entity.User;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.ChatMessageTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppService;
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final UserService userService;
    private final AppService appService;
    private final ChatHistoryService chatHistoryService;

    public AiChatController(AiCodeGeneratorFacade aiCodeGeneratorFacade,
                            UserService userService,
                            AppService appService,
                            ChatHistoryService chatHistoryService) {
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.userService = userService;
        this.appService = appService;
        this.chatHistoryService = chatHistoryService;
    }

    @GetMapping("/chat")
    public AiCodeGenResultVO chat(@RequestParam Long appId,
                                  @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                  HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        try {
            AiCodeGenResultVO vo = aiCodeGeneratorFacade.generateHtmlProject(message, appId);
            saveAssistantMessage(appId, userMsg, vo);
            return vo;
        } catch (RuntimeException e) {
            saveErrorMessage(appId, userMsg, e);
            throw e;
        }
    }

    @GetMapping("/chat/multi")
    public AiCodeGenResultVO chatMulti(@RequestParam Long appId,
                                       @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                       HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        try {
            AiCodeGenResultVO vo = aiCodeGeneratorFacade.generateMultiFileProject(message, appId);
            saveAssistantMessage(appId, userMsg, vo);
            return vo;
        } catch (RuntimeException e) {
            saveErrorMessage(appId, userMsg, e);
            throw e;
        }
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam Long appId,
                                 @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                 HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateHtmlProject(message, appId, new StreamCallback(
                token -> send(emitter, "token", token),
                vo -> {
                    send(emitter, "done", vo);
                    emitter.complete();
                    saveAssistantMessage(appId, userMsg, vo);
                },
                error -> {
                    send(emitter, "error", error.getMessage());
                    emitter.complete();
                    saveErrorMessage(appId, userMsg, error);
                }));
        return emitter;
    }

    @GetMapping(value = "/chat/multi/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatMultiStream(@RequestParam Long appId,
                                      @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                      HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        SseEmitter emitter = new SseEmitter(300_000L);
        aiCodeGeneratorFacade.streamGenerateMultiFileProject(message, appId, new StreamCallback(
                token -> send(emitter, "token", token),
                vo -> {
                    send(emitter, "done", vo);
                    emitter.complete();
                    saveAssistantMessage(appId, userMsg, vo);
                },
                error -> {
                    send(emitter, "error", error.getMessage());
                    emitter.complete();
                    saveErrorMessage(appId, userMsg, error);
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

    /**
     * 生成前落库用户消息，返回含自增 id 的记录用于关联 AI 回复
     */
    private ChatHistory saveUserMessage(Long appId, String message, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return chatHistoryService.saveMessage(appId, loginUser.getId(), message,
                ChatMessageTypeEnum.USER.getValue(), null);
    }

    /**
     * 生成完成后落库 AI 回复，内容与前端展示的 Markdown 格式一致
     */
    private void saveAssistantMessage(Long appId, ChatHistory userMsg, AiCodeGenResultVO vo) {
        try {
            chatHistoryService.saveMessage(appId, userMsg.getUserId(), buildAssistantContent(vo),
                    ChatMessageTypeEnum.ASSISTANT.getValue(), userMsg.getId());
        } catch (RuntimeException e) {
            // 历史落库失败不应影响生成结果返回
            log.warn("保存 AI 对话历史失败, appId={}", appId, e);
        }
    }

    /**
     * 生成失败时落库错误消息（截断避免存储超长堆栈）
     */
    private void saveErrorMessage(Long appId, ChatHistory userMsg, Throwable error) {
        try {
            String msg = error.getMessage();
            if (StrUtil.isBlank(msg)) {
                msg = "生成失败";
            }
            if (msg.length() > 500) {
                msg = msg.substring(0, 500);
            }
            chatHistoryService.saveMessage(appId, userMsg.getUserId(), msg,
                    ChatMessageTypeEnum.ERROR.getValue(), userMsg.getId());
        } catch (RuntimeException e) {
            log.warn("保存错误对话历史失败, appId={}", appId, e);
        }
    }

    /**
     * 将生成结果拼成 Markdown（description + 代码块），与前端展示格式保持一致
     */
    private String buildAssistantContent(AiCodeGenResultVO vo) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(vo.getDescription())) {
            sb.append(vo.getDescription());
        }
        if (StrUtil.isNotBlank(vo.getHtmlCode())) {
            sb.append("\n\n#### index.html\n```html\n").append(vo.getHtmlCode()).append("\n```");
        }
        if (StrUtil.isNotBlank(vo.getCssCode())) {
            sb.append("\n\n#### css/styles.css\n```css\n").append(vo.getCssCode()).append("\n```");
        }
        if (StrUtil.isNotBlank(vo.getJsCode())) {
            sb.append("\n\n#### js/app.js\n```javascript\n").append(vo.getJsCode()).append("\n```");
        }
        return sb.toString();
    }

    private void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }
}
