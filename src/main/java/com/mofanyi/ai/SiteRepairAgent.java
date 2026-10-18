package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.memory.AppSpecService;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ProjectCodeScanner;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.ManualEditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 站点修复 Agent：只负责构建错误修复与渲染问题修复的消息准备、快照保护、委托循环和变更记录。
 */
@Slf4j
@Component
public class SiteRepairAgent {

    private final AgentToolLoop agentToolLoop;
    private final CodeGenPromptRegistry promptRegistry;
    private final ManualEditService manualEditService;
    private final AppSpecService appSpecService;
    private final ProjectCodeScanner projectCodeScanner;
    private final CodeFileWriteService codeFileWriteService;

    public SiteRepairAgent(AgentToolLoop agentToolLoop,
                           CodeGenPromptRegistry promptRegistry,
                           ManualEditService manualEditService,
                           AppSpecService appSpecService,
                           ProjectCodeScanner projectCodeScanner,
                           CodeFileWriteService codeFileWriteService) {
        this.agentToolLoop = agentToolLoop;
        this.promptRegistry = promptRegistry;
        this.manualEditService = manualEditService;
        this.appSpecService = appSpecService;
        this.projectCodeScanner = projectCodeScanner;
        this.codeFileWriteService = codeFileWriteService;
    }

    /**
     * 构建失败修复。现有行为保持不变：传入 errorLog 和工程相关文件快照，修复后记录改动。
     */
    public void fixBuildError(Long appId, String errorLog, AiCallContext ctx,
                              AgentProgressListener listener, ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(errorLog == null || errorLog.isBlank(), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(ctx == null, ErrorCode.PARAMS_ERROR);

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.vueFix()));
        messages.add(new UserMessage("构建失败，错误日志如下：\n" + errorLog
                + "\n\n当前工程相关文件内容：\n" + buildFilesContext(appId)
                + "\n\n请修复导致报错的文件后调用 finish 结束。"));

        agentToolLoop.run(new AgentToolLoop.ToolLoopRequest(
                CodeGenTypeEnum.VUE, appId, messages, files, finished, description,
                null, ctx, listener, List.of(), reasoningLevel));
        appSpecService.recordChange(CodeGenTypeEnum.VUE, appId, "构建修复",
                "修复构建错误；改动文件：" + String.join("、", files.keySet()));
    }

    /**
     * 渲染问题修复。snapshot → run → finally replay 的时机与旧实现一致。
     */
    public void fixRenderIssues(CodeGenTypeEnum type, Long appId, String issueText,
                                AiCallContext ctx, AgentProgressListener listener,
                                ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(type == null || type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持渲染修复");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(issueText), ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(ctx == null, ErrorCode.PARAMS_ERROR);

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.renderFix()));
        messages.add(new UserMessage("渲染验证问题清单：\n" + issueText
                + "\n\n当前工程文件清单：\n" + buildFileIndex(type, appId)
                + "\n\n请先 readFile 读取涉及的文件，最小改动后用 writeFile 覆盖，改完调用 finish。"));

        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            agentToolLoop.run(new AgentToolLoop.ToolLoopRequest(
                    type, appId, messages, files, finished, description,
                    null, ctx, listener, List.of(), reasoningLevel));
        } finally {
            manualEditService.replay(type, appId, userEdits);
        }
        appSpecService.recordChange(type, appId, "渲染修复",
                "修复渲染问题；改动文件：" + String.join("、", files.keySet()));
    }

    private String buildFileIndex(CodeGenTypeEnum type, Long appId) {
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        java.io.File root = new java.io.File(projectDir);
        List<java.io.File> codeFiles = projectCodeScanner.collectCodeFiles(root);
        StringBuilder sb = new StringBuilder();
        for (java.io.File file : codeFiles) {
            sb.append("- ").append(projectCodeScanner.relativePath(root, file)).append("\n");
        }
        return sb.length() == 0 ? "（暂无文件）" : sb.toString();
    }

    private String buildFilesContext(Long appId) {
        List<VueFileResult> files = codeFileWriteService.readVueProject(
                codeFileWriteService.getProjectDir(CodeGenTypeEnum.VUE, appId));
        StringBuilder sb = new StringBuilder();
        for (VueFileResult file : files) {
            String content = file.getContent();
            if (content != null && content.length() > 8000) {
                sb.append("===== ").append(file.getPath()).append(" (内容过长，截断) =====\n")
                        .append(content, 0, 8000).append("\n\n");
            } else {
                sb.append("===== ").append(file.getPath()).append(" =====\n")
                        .append(content).append("\n\n");
            }
        }
        return sb.toString();
    }
}
