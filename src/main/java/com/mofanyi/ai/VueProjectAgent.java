package com.mofanyi.ai;

import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.ai.model.VueProjectResult;
import com.mofanyi.ai.memory.AgentMemoryService;
import com.mofanyi.ai.memory.AppSpecService;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.ProjectCodeScanner;
import com.mofanyi.core.ImageMaterialPlanner;
import com.mofanyi.core.TokenUsageAccumulator;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通用代码生成 Agent：生成 html / multi_file / vue 三种工程。
 *
 * <p>本类只负责生成上下文（素材、规格、记忆）、结果组装和可视化修改入口；
 * 工具循环及其预算、取消、压缩、推理档位全部委托给 {@link AgentToolLoop}。
 */
@Slf4j
@Component
public class VueProjectAgent {

    private final CodeGenPromptRegistry promptRegistry;
    private final AgentToolLoop agentToolLoop;
    private final SiteRepairAgent siteRepairAgent;
    private final com.mofanyi.ai.reasoning.ReasoningProfileRegistry reasoningProfileRegistry;
    private final AgentMemoryService agentMemoryService;
    private final AppSpecService appSpecService;
    private final ManualEditService manualEditService;
    private final ProjectCodeScanner projectCodeScanner;
    private final ImageMaterialPlanner imageMaterialPlanner;
    private final com.mofanyi.mapper.AppImageMapper appImageMapper;

    public VueProjectAgent(CodeGenPromptRegistry promptRegistry,
                           AgentToolLoop agentToolLoop,
                           SiteRepairAgent siteRepairAgent,
                           com.mofanyi.ai.reasoning.ReasoningProfileRegistry reasoningProfileRegistry,
                           AgentMemoryService agentMemoryService,
                           AppSpecService appSpecService,
                           ManualEditService manualEditService,
                           ProjectCodeScanner projectCodeScanner,
                           ImageMaterialPlanner imageMaterialPlanner,
                           com.mofanyi.mapper.AppImageMapper appImageMapper) {
        this.promptRegistry = promptRegistry;
        this.agentToolLoop = agentToolLoop;
        this.siteRepairAgent = siteRepairAgent;
        this.reasoningProfileRegistry = reasoningProfileRegistry;
        this.agentMemoryService = agentMemoryService;
        this.appSpecService = appSpecService;
        this.manualEditService = manualEditService;
        this.projectCodeScanner = projectCodeScanner;
        this.imageMaterialPlanner = imageMaterialPlanner;
        this.appImageMapper = appImageMapper;
    }

    /**
     * 逐文件回调进度：每写入一个文件触发一次，携带文件路径与完整内容（供前端流式展示代码）。
     * 兼容门面等外部调用方的旧签名；能力来自 {@link AgentProgressListener}。
     */
    public interface VueProgressListener extends AgentProgressListener {
    }

    /**
     * 生成 Vue3 工程（兼容原调用）：委托通用生成，按 vue 类型执行。
     */
    public VueProjectResult generate(Long appId, String userMessage, VueProgressListener listener) {
        GenerateResult result = generate(CodeGenTypeEnum.VUE, appId, userMessage,
                AiCallContext.of(appId, null, "vue"), listener,
                reasoningProfileRegistry.defaultReasoningLevel());
        return result.getVueResult();
    }

    /**
     * 通用生成入口：按类型逐文件生成工程。
     *
     * @param type        代码生成类型（html / multi_file / vue）
     * @param appId       应用 id
     * @param userMessage 用户需求
     * @param ctx         可观测性上下文（appId/userId/callType）
     * @param listener    进度回调，可为 null
     * @return 按类型组装的结果
     */
    public GenerateResult generate(CodeGenTypeEnum type, Long appId, String userMessage,
                                   AiCallContext ctx, VueProgressListener listener) {
        return generate(type, appId, userMessage, ctx, listener,
                reasoningProfileRegistry.defaultReasoningLevel());
    }

    public GenerateResult generate(CodeGenTypeEnum type, Long appId, String userMessage,
                                   AiCallContext ctx, VueProgressListener listener,
                                   com.mofanyi.ai.reasoning.ReasoningLevel reasoningLevel) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userMessage == null || userMessage.isBlank(), ErrorCode.PARAMS_ERROR, "用户需求不能为空");
        ThrowUtils.throwIf(reasoningLevel == null, ErrorCode.PARAMS_ERROR, "推理档位不能为空");

        // 重新生成前清空该应用旧的嵌入图片记录，避免重复插入
        try {
            appImageMapper.deleteByAppId(appId);
        } catch (RuntimeException e) {
            log.warn("清空旧嵌入图片记录失败，appId={}", appId, e);
        }

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.system(type)));
        // 注入"应用现状"（规格摘要 + 代码库索引）：续接任务时模型需要的是应用现状，而非聊天原文
        String specSection = appSpecService.buildSpecSection(type, appId);
        if (StrUtil.isNotBlank(specSection)) {
            messages.add(new SystemMessage(specSection));
        }
        // 素材先行（plan-refactor 6.6）：页面构建**之前**把图片素材备好，并把清单作为事实注入。
        // 取代原先"页面先生成、事后用正则补图"（ImageAutoFiller 已删除）——那次序导致页面围绕空占位设计、补图靠猜。
        List<ImageMaterialPlanner.Material> materials = imageMaterialPlanner.plan(type, appId, userMessage);
        String materialSection = imageMaterialPlanner.toPromptSection(materials);
        if (StrUtil.isNotBlank(materialSection)) {
            messages.add(new SystemMessage(materialSection));
        }
        // 注入对话记忆作为前置上下文（优先 Redis 热窗口，miss 时从 MySQL 冷启动回灌）
        messages.addAll(agentMemoryService.loadContext(type, appId, userMessage, specSection));
        messages.add(new UserMessage(userMessage));

        // [promptTokens, completionTokens] 累加器，工具循环各轮模型调用后写入
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        // 生成前快照用户手动修改：重新生成是整文件覆盖，会把 @user-edit 标记块一起抹掉（plan-refactor 6.2）
        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            agentToolLoop.run(new AgentToolLoop.ToolLoopRequest(
                    type, appId, messages, files, finished, description, usage, ctx,
                    listener, materials, reasoningLevel));
            // 生成完成后扫描项目代码，把嵌入的图片 URL 落库
            projectCodeScanner.scanAndSaveImages(type, appId);
        } finally {
            // 成功 / 失败 / 被手动停止都要重放，用户手动改动不因一次生成而丢失
            manualEditService.replay(type, appId, userEdits);
        }

        // 状态只进应用规格；对话记忆只保留用户意图，避免同一摘要注入两份
        String changeSummary = buildChangeSummary(type, description.get(), files.keySet());
        agentMemoryService.appendTurn(appId, userMessage, null);
        appSpecService.recordChange(type, appId, "生成", changeSummary);

        GenerateResult result = assembleResult(type, appId, files, description.get());
        result.setUsage(usage.snapshot());
        return result;
    }

    /**
     * 构造应用规格的变更摘要：生成类型 + 产出文件索引 + 简短说明。
     * 状态摘要只写入规格，不再复制到对话记忆。
     */
    private String buildChangeSummary(CodeGenTypeEnum type, String description, Set<String> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("已完成").append(type.getValue()).append("生成。");
        if (paths != null && !paths.isEmpty()) {
            sb.append("产出文件：").append(String.join("、", paths)).append("。");
        }
        if (StrUtil.isNotBlank(description)) {
            sb.append("说明：").append(StrUtil.maxLength(description, 500));
        }
        return sb.toString();
    }


    /**
     * 兼容旧调用方：修复实现已迁到 {@link SiteRepairAgent}。
     */
    public void fixBuildError(Long appId, String errorLog, AiCallContext ctx, VueProgressListener listener) {
        siteRepairAgent.fixBuildError(appId, errorLog, ctx, listener,
                reasoningProfileRegistry.defaultReasoningLevel());
    }

    public void fixBuildError(Long appId, String errorLog, AiCallContext ctx,
                              VueProgressListener listener,
                              com.mofanyi.ai.reasoning.ReasoningLevel reasoningLevel) {
        siteRepairAgent.fixBuildError(appId, errorLog, ctx, listener, reasoningLevel);
    }

    /**
     * 兼容旧调用方：渲染修复实现已迁到 {@link SiteRepairAgent}。
     */
    public void fixRenderIssues(CodeGenTypeEnum type, Long appId, String issueText,
                                AiCallContext ctx, VueProgressListener listener) {
        siteRepairAgent.fixRenderIssues(type, appId, issueText, ctx, listener,
                reasoningProfileRegistry.defaultReasoningLevel());
    }

    public void fixRenderIssues(CodeGenTypeEnum type, Long appId, String issueText,
                                AiCallContext ctx, VueProgressListener listener,
                                com.mofanyi.ai.reasoning.ReasoningLevel reasoningLevel) {
        siteRepairAgent.fixRenderIssues(type, appId, issueText, ctx, listener, reasoningLevel);
    }

    /**
     * 按类型组装生成结果
     */
    private GenerateResult assembleResult(CodeGenTypeEnum type, Long appId, Map<String, String> files,
                                          String description) {
        GenerateResult result = new GenerateResult();
        result.setDescription(description);
        if (type == CodeGenTypeEnum.VUE) {
            VueProjectResult vueResult = new VueProjectResult();
            vueResult.setDescription(description);
            files.forEach((path, content) -> {
                VueFileResult f = new VueFileResult();
                f.setPath(path);
                f.setContent(content);
                vueResult.getFiles().add(f);
            });
            result.setVueResult(vueResult);
        }
        return result;
    }

    /**
     * 通用生成结果：vue 类型含文件清单；html/multi_file 由门面从项目目录读取
     */
    public static class GenerateResult {
        private String description;
        private VueProjectResult vueResult;
        private TokenUsageAccumulator.TokenUsage usage;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public VueProjectResult getVueResult() {
            return vueResult;
        }

        public void setVueResult(VueProjectResult vueResult) {
            this.vueResult = vueResult;
        }

        public TokenUsageAccumulator.TokenUsage getUsage() {
            return usage;
        }

        public void setUsage(TokenUsageAccumulator.TokenUsage usage) {
            this.usage = usage;
        }
    }
}
