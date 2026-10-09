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
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.model.vo.AiCodeGenResultVO;
import com.mofanyi.service.AppAuthService;
import com.mofanyi.service.ChatHistoryService;
import com.mofanyi.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final AppAuthService appAuthService;
    private final ChatHistoryService chatHistoryService;

    public AiChatController(AiCodeGeneratorFacade aiCodeGeneratorFacade,
                            UserService userService,
                            AppAuthService appAuthService,
                            ChatHistoryService chatHistoryService) {
        this.aiCodeGeneratorFacade = aiCodeGeneratorFacade;
        this.userService = userService;
        this.appAuthService = appAuthService;
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
        SseEmitter emitter = new SseEmitter(600_000L);
        aiCodeGeneratorFacade.streamGenerateHtmlProject(message, appId, new StreamCallback(
                token -> send(emitter, "agent_progress", token),
                reasoning -> send(emitter, "reasoning", reasoning),
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
        SseEmitter emitter = new SseEmitter(600_000L);
        aiCodeGeneratorFacade.streamGenerateMultiFileProject(message, appId, new StreamCallback(
                token -> send(emitter, "agent_progress", token),
                reasoning -> send(emitter, "reasoning", reasoning),
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

    @GetMapping("/chat/vue")
    public AiCodeGenResultVO chatVue(@RequestParam Long appId,
                                     @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                     HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        try {
            AiCodeGenResultVO vo = aiCodeGeneratorFacade.generateVueProject(message, appId);
            saveAssistantMessage(appId, userMsg, vo);
            return vo;
        } catch (RuntimeException e) {
            saveErrorMessage(appId, userMsg, e);
            throw e;
        }
    }

    /**
     * 流式生成 Vue3 工程：SSE 推送 agent_progress（每个文件写入）与 done（最终 VO）。
     * onToken 收到的是 "path:size" 格式的文件进度文本。
     */
    @GetMapping(value = "/chat/vue/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatVueStream(@RequestParam Long appId,
                                    @RequestParam(defaultValue = "你好，请简单介绍一下你自己") String message,
                                    HttpServletRequest request) {
        ChatHistory userMsg = saveUserMessage(appId, message, request);
        SseEmitter emitter = new SseEmitter(600_000L);
        aiCodeGeneratorFacade.streamGenerateVueProject(message, appId, new StreamCallback(
                token -> send(emitter, "agent_progress", token),
                reasoning -> send(emitter, "reasoning", reasoning),
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
     * 可视化修改：给定点选元素上下文 + 用户意图，让 agent 精确定位源码并最小改动（SSE 逐文件返回）。
     * 仅应用创建者可修改；当前支持 html / multi_file（vue 用构建产物预览，暂不支持）。
     */
    @GetMapping(value = "/modify/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter modifyStream(@RequestParam Long appId,
                                   @RequestParam(defaultValue = "") String elementContext,
                                   @RequestParam String instruction,
                                   HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");
        User loginUser = userService.getLoginUser(request);
        App app = appAuthService.requireOwner(appId, loginUser);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持可视化修改");
        // 防止超大上下文/意图刷爆 token，兜底截断
        String ctx = elementContext == null ? "" : StrUtil.maxLength(elementContext, 2000);
        String inst = StrUtil.maxLength(instruction, 500);
        SseEmitter emitter = new SseEmitter(600_000L);
        aiCodeGeneratorFacade.streamModifyProject(type, appId, ctx, inst, new StreamCallback(
                token -> send(emitter, "agent_progress", token),
                reasoning -> send(emitter, "reasoning", reasoning),
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
     * 撤销可视化修改：写回修改前的文件快照。仅创建者；body 为文件 Map（path -> content）。
     */
    @PostMapping("/modify/undo")
    public BaseResponse<AiCodeGenResultVO> undoModify(@RequestParam Long appId,
                                                      @RequestBody java.util.Map<String, String> files,
                                                      HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        App app = appAuthService.requireOwner(appId, loginUser);
        CodeGenTypeEnum type = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持可视化修改");
        AiCodeGenResultVO vo = aiCodeGeneratorFacade.restoreSource(type, appId, files);
        return ResultUtils.success(vo);
    }

    /**
     * 渲染修复状态：交付后修复是**异步**的，前端可轮询此接口得知修复进度并刷新预览。
     * 返回 ok / fixing / fixed / still_has_problems / skipped / error。
     */
    @GetMapping("/render-fix/status")
    public BaseResponse<String> renderFixStatus(@RequestParam Long appId, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        appAuthService.requireOwner(appId, loginUser);
        return ResultUtils.success(aiCodeGeneratorFacade.renderFixStatus(appId));
    }

    /**
     * 手动停止 AI 生成（仅本人可停止）
     */
    @PostMapping("/stop")
    public BaseResponse<Boolean> stopGenerate(@RequestParam Long appId, HttpServletRequest request) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        appAuthService.requireOwner(appId, loginUser);
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
     * 将生成结果拼成 Markdown（description + 代码块），与前端展示格式保持一致。
     * vue 模式输出 description + 文件清单（每文件代码块）。
     */
    private String buildAssistantContent(AiCodeGenResultVO vo) {
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(vo.getDescription())) {
            sb.append(vo.getDescription());
        }
        if (vo.getFiles() != null && !vo.getFiles().isEmpty()) {
            for (com.mofanyi.ai.model.VueFileResult f : vo.getFiles()) {
                if (StrUtil.isNotBlank(f.getPath()) && f.getContent() != null) {
                    sb.append("\n\n#### ").append(f.getPath()).append("\n```js\n")
                            .append(f.getContent()).append("\n```");
                }
            }
            return sb.toString();
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
