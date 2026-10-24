package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.memory.AppSpecService;
import com.mofanyi.ai.reasoning.ReasoningLevel;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ProjectCodeScanner;
import com.mofanyi.core.TokenUsageAccumulator;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.job.GenerationExecutionCondition;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.ManualEditService;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可视化修改 Agent：准备点选上下文、保护用户手动改动、调用共享循环并记录规格变化。
 */
@Component
@Conditional(GenerationExecutionCondition.class)
public class SiteModifyAgent {

    private final AgentToolLoop agentToolLoop;
    private final CodeGenPromptRegistry promptRegistry;
    private final ManualEditService manualEditService;
    private final AppSpecService appSpecService;
    private final ProjectCodeScanner projectCodeScanner;
    private final CodeFileWriteService codeFileWriteService;

    public SiteModifyAgent(AgentToolLoop agentToolLoop,
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

    public Map<String, String> modifyGeneratedSite(CodeGenTypeEnum type, Long appId,
                                                   String elementContext, String instruction,
                                                   TokenUsageAccumulator usage, AiCallContext ctx,
                                                   AgentProgressListener listener,
                                                   ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");
        ThrowUtils.throwIf(ctx == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(reasoningLevel == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.modify()));
        String elementCtx = StrUtil.isBlank(elementContext)
                ? "（无具体元素，基于整体需求修改）" : elementContext;
        messages.add(new UserMessage("用户想修改当前页面。\n\n【目标元素上下文】\n" + elementCtx
                + "\n\n【用户修改意图】\n" + instruction
                + "\n\n【当前工程文件清单】\n" + buildFileIndex(type, appId)
                + "\n\n请先 readFile 读取涉及的文件，定位目标元素对应代码段，"
                + "最小改动后用 writeFile 覆盖，改完调用 finish。"));

        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            agentToolLoop.run(new AgentToolLoop.ToolLoopRequest(
                    type, appId, messages, files, finished, description, usage, ctx,
                    listener, List.of(), reasoningLevel));
        } finally {
            manualEditService.replay(type, appId, userEdits);
        }
        appSpecService.recordChange(type, appId, "可视化修改",
                instruction + "；改动文件：" + String.join("、", files.keySet()));
        return files;
    }

    public void restoreFiles(CodeGenTypeEnum type, Long appId, Map<String, String> files) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(files == null || files.isEmpty(), ErrorCode.PARAMS_ERROR, "还原内容为空");
        ThrowUtils.throwIf(files.size() > 100, ErrorCode.PARAMS_ERROR, "还原文件数量过多");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            ToolPaths.validate(entry.getKey());
            codeFileWriteService.saveProjectFile(type, appId, entry.getKey(), entry.getValue());
        }
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
}
