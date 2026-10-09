package com.mofanyi.ai;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.ai.model.VueProjectResult;
import com.mofanyi.ai.memory.AgentMemoryService;
import com.mofanyi.ai.memory.AppSpecService;
import com.mofanyi.ai.task.GenerationTaskRegistry;
import com.mofanyi.ai.tools.AgentProgressListener;
import com.mofanyi.ai.tools.DiagramTool;
import com.mofanyi.ai.tools.FinishTool;
import com.mofanyi.ai.tools.ReadFileTool;
import com.mofanyi.ai.tools.SearchImageTool;
import com.mofanyi.ai.tools.WriteFileTool;
import com.mofanyi.core.AiCallContext;
import com.mofanyi.core.AiCallListener;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.ProjectCodeScanner;
import com.mofanyi.core.ImageAutoFiller;
import com.mofanyi.core.TokenUsageAccumulator;
import com.mofanyi.core.SiteImageService;
import com.mofanyi.core.ToolPaths;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import com.mofanyi.service.ManualEditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 通用代码生成 Agent：生成 html / multi_file / vue 三种工程。
 *
 * <p><b>工具循环的真实形态（勿被旧注释误导）</b>：工具由框架在**一次 {@code chatModel.stream()} 内部**
 * 自动执行（`DefaultToolCallingChatOptions` 默认开启内部工具执行），模型在一次调用里自行决定写哪些文件、
 * 何时调用 finish。外层 {@code runToolLoop} 的 for 循环在首次成功后即 break，实际只承担
 * <b>异常重试</b>与<b>取消/预算检查</b>，并不是"一轮一个文件"的逐步循环。
 *
 * <p>之所以用 {@code stream()} 而非 {@code call()}：为了拿到模型的 reasoning 增量（透传给前端）。
 * 逐文件进度来自 writeFile 工具回调，与是否自管循环无关。
 */
@Slf4j
@Component
public class VueProjectAgent {

    /**
     * 单次生成最大工具调用轮数（一个文件一轮），防止模型不收敛无限循环
     */
    private static final int MAX_STEPS = 20;

    /**
     * 单次生成的墙钟预算（默认 10 分钟）：超过即终止，避免任务长期占用 appId 互斥
     */
    private static final long MAX_LOOP_MILLIS = 600_000L;

    /**
     * 单次生成的累计 token 预算（prompt + completion），超过即终止。
     *
     * <p>默认值由 20 万上调到 80 万（S5-3 收尾）：实测同一 portfolio prompt 在框架路径单次调用就烧掉 46 万，
     * 20 万会让自管循环的大页面在跑到一半时被判超预算。预算的定位是"拦住跑飞"，主力闸门仍是
     * {@link #MAX_STEPS} 与 {@link #MAX_LOOP_MILLIS}。可用环境变量 {@code CODEGEN_MAX_LOOP_TOKENS} 覆盖。
     */
    @org.springframework.beans.factory.annotation.Value("${codegen.agent.max-loop-tokens:800000}")
    private long maxLoopTokens;

    private final ChatModel chatModel;
    private final CodeGenPromptRegistry promptRegistry;

    /**
     * Agent 循环形态开关（plan-refactor S5）：true=自管循环（逐步预算生效），false=框架内部循环（现状，默认）。
     * 本步只加开关与打点骨架，循环形态不变 —— 开关暂时是 no-op。
     */
    @org.springframework.beans.factory.annotation.Value("${codegen.agent.self-managed-loop.enabled:false}")
    private boolean selfManagedLoopEnabled;
    private final CodeFileWriteService codeFileWriteService;
    private final AgentMemoryService agentMemoryService;
    private final AppSpecService appSpecService;
    private final GenerationTaskRegistry taskRegistry;
    private final ManualEditService manualEditService;
    private final ProjectCodeScanner projectCodeScanner;
    private final ImageAutoFiller imageAutoFiller;
    private final com.mofanyi.mapper.AppImageMapper appImageMapper;
    private final WriteFileTool writeFileTool;
    private final ReadFileTool readFileTool;
    private final SearchImageTool searchImageTool;
    private final DiagramTool diagramTool;
    private final FinishTool finishTool;
    private final AiCallListener aiCallListener;

    public VueProjectAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                           CodeGenPromptRegistry promptRegistry,
                           CodeFileWriteService codeFileWriteService,
                           AgentMemoryService agentMemoryService,
                           AppSpecService appSpecService,
                           GenerationTaskRegistry taskRegistry,
                           ManualEditService manualEditService,
                           ProjectCodeScanner projectCodeScanner,
                           ImageAutoFiller imageAutoFiller,
                           com.mofanyi.mapper.AppImageMapper appImageMapper,
                           WriteFileTool writeFileTool,
                           ReadFileTool readFileTool,
                           SearchImageTool searchImageTool,
                           DiagramTool diagramTool,
                           FinishTool finishTool,
                           AiCallListener aiCallListener) {
        this.chatModel = chatModel;
        this.promptRegistry = promptRegistry;
        this.codeFileWriteService = codeFileWriteService;
        this.agentMemoryService = agentMemoryService;
        this.appSpecService = appSpecService;
        this.taskRegistry = taskRegistry;
        this.manualEditService = manualEditService;
        this.projectCodeScanner = projectCodeScanner;
        this.imageAutoFiller = imageAutoFiller;
        this.appImageMapper = appImageMapper;
        this.writeFileTool = writeFileTool;
        this.readFileTool = readFileTool;
        this.searchImageTool = searchImageTool;
        this.diagramTool = diagramTool;
        this.finishTool = finishTool;
        this.aiCallListener = aiCallListener;
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
                AiCallContext.of(appId, null, "vue"), listener);
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
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(userMessage == null || userMessage.isBlank(), ErrorCode.PARAMS_ERROR, "用户需求不能为空");

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
        // 注入对话记忆作为前置上下文（优先 Redis 热窗口，miss 时从 MySQL 冷启动回灌）
        messages.addAll(agentMemoryService.loadContext(appId, userMessage));
        messages.add(new UserMessage(userMessage));

        // [promptTokens, completionTokens] 累加器，runToolLoop 各轮模型调用后写入
        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        // 生成前快照用户手动修改：重新生成是整文件覆盖，会把 @user-edit 标记块一起抹掉（plan-refactor 6.2）
        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            runToolLoop(type, appId, messages, files, finished, description, usage, ctx, listener);
            // 强制补图：模型可能漏调 searchImage，导致页面缺图。扫描代码中的空图片字段/占位图并填真实图。
            imageAutoFiller.autoFill(type, appId, userMessage);
            // 生成完成后扫描项目代码，把嵌入的图片 URL 落库（含补图后的真实 URL）
            projectCodeScanner.scanAndSaveImages(type, appId);
        } finally {
            // 成功 / 失败 / 被手动停止都要重放，用户手动改动不因一次生成而丢失
            manualEditService.replay(type, appId, userEdits);
        }

        // 记录本轮对话到记忆：只存说明与文件索引，不存全量代码（代码在磁盘上，需要时 readFile 取）
        String summary = buildMemorySummary(type, description.get(), files.keySet());
        agentMemoryService.appendTurn(appId, userMessage, summary);
        // 同步更新应用规格摘要（供后续续接时理解"这个应用现在是什么样"）
        appSpecService.recordChange(type, appId, "生成", summary);

        GenerateResult result = assembleResult(type, appId, files, description.get());
        result.setUsage(usage.snapshot());
        return result;
    }

    /**
     * 构造写入记忆的 AI 回复摘要（去代码化）：生成类型 + 产出文件索引 + 简短说明。
     * 刻意不写全量代码——代码已落盘，模型需要时用 readFile 取，避免上下文被代码撑爆。
     */
    private String buildMemorySummary(CodeGenTypeEnum type, String description, Set<String> paths) {
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
     * 修复构建错误：给定错误日志，让模型用 writeFile 覆盖出问题的文件，直到 finish 或达最大轮数。
     *
     * @param appId    应用 id
     * @param errorLog 构建错误日志
     * @param listener 进度回调，可为 null
     */
    public void fixBuildError(Long appId, String errorLog, AiCallContext ctx, VueProgressListener listener) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(errorLog == null || errorLog.isBlank(), ErrorCode.PARAMS_ERROR);

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");

        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.vueFix()));
        messages.add(new UserMessage("构建失败，错误日志如下：\n" + errorLog
                + "\n\n当前工程相关文件内容：\n" + buildFilesContext(appId)
                + "\n\n请修复导致报错的文件后调用 finish 结束。"));

        runToolLoop(CodeGenTypeEnum.VUE, appId, messages, files, finished, description, null, ctx, listener);
        appSpecService.recordChange(CodeGenTypeEnum.VUE, appId, "构建修复",
                "修复构建错误；改动文件：" + String.join("、", files.keySet()));
    }

    /**
     * 渲染问题修复：给定无头浏览器实拍采集的问题清单（图裂/溢出/空白/console 报错），
     * 让 agent 读源码、最小改动修复（plan-agent-pipeline.md P0-4）。
     *
     * @param type      代码生成类型（html / multi_file）
     * @param appId     应用 id
     * @param issueText 渲染验证问题清单文本
     * @param ctx       可观测性上下文
     * @param listener  进度回调，可为 null
     */
    public void fixRenderIssues(CodeGenTypeEnum type, Long appId, String issueText,
                                AiCallContext ctx, VueProgressListener listener) {
        ThrowUtils.throwIf(type == null || type == CodeGenTypeEnum.VUE,
                ErrorCode.PARAMS_ERROR, "Vue3 工程暂不支持渲染修复");
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(issueText), ErrorCode.PARAMS_ERROR);

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.renderFix()));
        messages.add(new UserMessage("渲染验证问题清单：\n" + issueText
                + "\n\n当前工程文件清单：\n" + buildFileIndex(type, appId)
                + "\n\n请先 readFile 读取涉及的文件，最小改动后用 writeFile 覆盖，改完调用 finish。"));

        // 修复也是整文件覆盖，同样要保住用户的手动修改
        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            runToolLoop(type, appId, messages, files, finished, description, null, ctx, listener);
        } finally {
            manualEditService.replay(type, appId, userEdits);
        }
        appSpecService.recordChange(type, appId, "渲染修复",
                "修复渲染问题；改动文件：" + String.join("、", files.keySet()));
    }

    /**
     * 可视化修改：给定被选中元素的上下文（选择器/标签/文本/结构摘要）+ 用户修改意图，
     * 让 agent 通过 readFile/writeFile 精确定位源码并最小改动。
     *
     * @param type           代码生成类型（当前支持 html / multi_file，vue 用构建产物做预览暂不支持）
     * @param appId          应用 id
     * @param elementContext 前端点选生成的元素上下文，可为空串（表示按整页意图改）
     * @param instruction    用户自然语言修改意图
     * @param usage          [prompt, completion] token 累加器，可为 null
     * @param listener       进度回调（每写一个文件触发一次），可为 null
     * @return 实际被改动的文件（path -> 新内容）
     */
    public Map<String, String> modifyGeneratedSite(CodeGenTypeEnum type, Long appId, String elementContext,
                                                   String instruction, TokenUsageAccumulator usage, AiCallContext ctx,
                                                   VueProgressListener listener) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(promptRegistry.modify()));
        String elementCtx = StrUtil.isBlank(elementContext) ? "（无具体元素，基于整体需求修改）" : elementContext;
        messages.add(new UserMessage("用户想修改当前页面。\n\n【目标元素上下文】\n" + elementCtx
                + "\n\n【用户修改意图】\n" + instruction
                + "\n\n【当前工程文件清单】\n" + buildFileIndex(type, appId)
                + "\n\n请先 readFile 读取涉及的文件，定位目标元素对应代码段，最小改动后用 writeFile 覆盖，改完调用 finish。"));
        // 可视化修改同样是整文件覆盖，先快照再重放，避免顺手抹掉用户的手动修改
        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            runToolLoop(type, appId, messages, files, finished, description, usage, ctx, listener);
        } finally {
            manualEditService.replay(type, appId, userEdits);
        }
        // 可视化修改同样要更新规格摘要（否则摘要会滞后于代码）
        appSpecService.recordChange(type, appId, "可视化修改",
                instruction + "；改动文件：" + String.join("、", files.keySet()));
        return files;
    }

    /**
     * 还原源码：把给定的文件快照写回工程（供可视化修改的"撤销"）。逐个路径校验 + 覆盖。
     */
    public void restoreFiles(CodeGenTypeEnum type, Long appId, Map<String, String> files) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(files == null || files.isEmpty(), ErrorCode.PARAMS_ERROR, "还原内容为空");
        ThrowUtils.throwIf(files.size() > 100, ErrorCode.PARAMS_ERROR, "还原文件数量过多");
        for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            ToolPaths.validate(e.getKey());
            codeFileWriteService.saveProjectFile(type, appId, e.getKey(), e.getValue());
        }
    }

    /**
     * 手动 tool-calling 循环：每轮把历史消息发给模型，若返回工具调用则执行并拼回结果继续，
     * 直到模型不再调工具或达最大轮数。
     */
    private void runToolLoop(CodeGenTypeEnum type, Long appId, List<Message> messages, Map<String, String> files,
                             AtomicBoolean finished, AtomicReference<String> description, TokenUsageAccumulator usage,
                             AiCallContext ctx, VueProgressListener listener) {
        List<ToolCallback> toolCallbacks = List.of(
                writeFileTool.build(type, appId, files, listener),
                readFileTool.build(type, appId),
                searchImageTool.build(appId),
                diagramTool.build(appId),
                finishTool.build(finished, description));
        var options = DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).build();

        // S5-2：开关开启时走自管循环（逐轮预算/取消检查才真正生效）
        if (selfManagedLoopEnabled) {
            runToolLoopSelfManaged(appId, messages, finished, description, usage, ctx, toolCallbacks);
            return;
        }

        // 连续异常计数：模型返回被截断（工具参数 JSON 不完整）等可恢复错误时重试，连续多次失败才终止
        int consecutiveFailures = 0;
        long loopStart = System.currentTimeMillis();
        int stepCount = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            stepCount++;
            // S5 打点：每轮开始前记录"轮次开始时的累计用量"（prompt/completion 分列）。
            // 分列后才有 ③ 需要的 promptTokens 单独口径——累计 token 混着 completion 时算不出上下文增长。
            // 注意：外层恒为 1 轮（框架内部完成全部轮次），自管循环落地后这里才是真实轮次。
            log.info("[agent] round={} 模式={} prompt={} completion={} 累计token={}/{} 已耗时={}ms", stepCount,
                    selfManagedLoopEnabled ? "self" : "framework",
                    usage == null ? 0 : usage.promptTokens(),
                    usage == null ? 0 : usage.completionTokens(),
                    usage == null ? 0 : usage.totalTokens(),
                    maxLoopTokens, System.currentTimeMillis() - loopStart);
            ChatResponse resp;
            // 每次模型调用的耗时与本次 token 增量（用于可观测性）
            long callStart = System.currentTimeMillis();
            long beforeIn = usage == null ? 0 : usage.promptTokens();
            long beforeOut = usage == null ? 0 : usage.completionTokens();
            try {
                // 用流式调用聚合，以便拿到模型思考过程（reasoning_content）增量
                resp = streamAndAggregate(new Prompt(messages, options),
                        listener == null ? null : listener::onReasoning);
            } catch (RuntimeException e) {
                long ms = System.currentTimeMillis() - callStart;
                aiCallListener.onFailure(ctx, e, ms);
                log.warn("[agent] 模型响应异常（参数截断/解析失败），step={}，提示模型重试", step, e);
                if (++consecutiveFailures >= 3) {
                    log.error("[agent] 连续 3 次模型响应异常，终止生成，step={}", step);
                    break;
                }
                messages.add(new UserMessage("你上一步的返回内容被截断（工具调用参数不完整，解析失败）。"
                        + "请重新调用工具：若文件内容较大，请拆成多次 writeFile 调用，每次内容控制在 3000 字符以内；"
                        + "若已完成，直接调用 finish。"));
                continue;
            }
            consecutiveFailures = 0;
            // 手动停止检查：必须放在模型调用<b>返回之后</b>。
            // 工具回调里抛异常会被框架吞成"工具错误结果"喂回模型，只有在此处中断才真正终止生成。
            taskRegistry.checkCancelled(appId);
            // 预算检查（plan-agent-engineering 第五章）
            checkBudget(loopStart, usage == null ? 0 : usage.promptTokens(), usage == null ? 0 : usage.completionTokens(), maxLoopTokens);
            if (usage != null) { usage.accumulate(resp); }
            // 可观测性：记录本次调用（成功）
            long callMs = System.currentTimeMillis() - callStart;
            long inDelta = (usage == null ? 0 : usage.promptTokens()) - beforeIn;
            long outDelta = (usage == null ? 0 : usage.completionTokens()) - beforeOut;
            aiCallListener.onSuccess(ctx, finishReasonOf(resp), inDelta, outDelta, callMs);
            logRoundUsage("framework", stepCount, inDelta, outDelta, callMs);
            AssistantMessage output = resp.getResult().getOutput();
            // 工具由框架在 stream 内部自动执行（工具回调已通过 options 注册），聚合响应不含工具调用。
            // 模型直接返回文本时记下概述并结束本层循环（工具循环发生在一次流式调用内）。
            if (description.get().isBlank() && output.getText() != null) {
                description.set(output.getText());
            }
            break;
        }
        log.info("[agent] tool-loop 结束：外层 step={}，写文件数={}，总耗时={}ms",
                stepCount, files.size(), System.currentTimeMillis() - loopStart);
    }

    /**
     * 取响应的 finishReason（stop/length/tool_calls 等），取不到返回 null
     */
    /**
     * 预算校验：超出墙钟或累计 token 预算即抛业务异常终止（区别于"失败"，提示语里说明是超预算）。
     *
     * <p><b>生效范围（重要）</b>：框架在一次 {@code stream()} 内自己跑完工具循环，外层 for 首次成功即
     * {@code break}，所以这里主要拦住"多轮异常重试"与"超长单轮"；真正的逐步预算需要改成自管循环
     * （plan-refactor 7.1 拆类时一并做）。取消检查同理。
     */
    static void checkBudget(long loopStartMillis, long promptTokens, long completionTokens, long limitTokens) {
        long elapsed = System.currentTimeMillis() - loopStartMillis;
        ThrowUtils.throwIf(elapsed > MAX_LOOP_MILLIS, ErrorCode.OPERATION_ERROR,
                "生成超出时限（已 " + (elapsed / 1000) + "s，上限 " + (MAX_LOOP_MILLIS / 1000) + "s），已终止");
        long total = promptTokens + completionTokens;
        ThrowUtils.throwIf(total > limitTokens, ErrorCode.OPERATION_ERROR,
                "生成超出 token 预算（已 " + total + "，上限 " + limitTokens + "），已终止");
    }

    /**
     * 自管工具循环（S5-2，开关 {@code selfManagedLoopEnabled=true} 时启用）。
     *
     * <p>与"框架内部执行"的区别：关闭 {@code internalToolExecutionEnabled}，由本方法自己
     * 解析 tool_calls、执行工具、把 assistant 消息与工具结果<b>成对</b>写回历史，
     * 从而能在<b>每轮之间</b>做取消与预算检查——这才是 S5 的目的。
     *
     * <p><b>已知回退（方案 b）</b>：本路径用非流式 {@code call()}（tool_calls 天然完整），
     * 但拿不到 {@code reasoning} 增量，开关开启时 SSE 的 reasoning 事件为空。
     */
    private void runToolLoopSelfManaged(Long appId, List<Message> messages, AtomicBoolean finished,
                                        AtomicReference<String> description, TokenUsageAccumulator usage,
                                        AiCallContext ctx, List<ToolCallback> toolCallbacks) {
        var options = DefaultToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .internalToolExecutionEnabled(false)   // 关键：不让框架执行工具，改由本方法执行
                .build();
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback cb : toolCallbacks) {
            byName.put(cb.getToolDefinition().name(), cb);
        }
        long loopStart = System.currentTimeMillis();
        int consecutiveFailures = 0;
        for (int round = 1; round <= MAX_STEPS; round++) {
            taskRegistry.checkCancelled(appId);
            checkBudget(loopStart,
                    usage == null ? 0 : usage.promptTokens(),
                    usage == null ? 0 : usage.completionTokens(), maxLoopTokens);
            log.info("[agent] round={} 模式=self prompt={} completion={} 累计token={}/{} 已耗时={}ms", round,
                    usage == null ? 0 : usage.promptTokens(),
                    usage == null ? 0 : usage.completionTokens(),
                    usage == null ? 0 : usage.totalTokens(),
                    maxLoopTokens, System.currentTimeMillis() - loopStart);

            long callStart = System.currentTimeMillis();
            long beforeIn = usage == null ? 0 : usage.promptTokens();
            long beforeOut = usage == null ? 0 : usage.completionTokens();
            ChatResponse resp;
            try {
                resp = chatModel.call(new Prompt(messages, options));
            } catch (RuntimeException e) {
                aiCallListener.onFailure(ctx, e, System.currentTimeMillis() - callStart);
                if (++consecutiveFailures >= 3) {
                    throw e;
                }
                messages.add(new UserMessage("上一轮调用失败，请重试；内容较大时请拆小后再写。"));
                continue;
            }
            consecutiveFailures = 0;
            if (usage != null) {
                usage.accumulate(resp);
            }
            long callMs = System.currentTimeMillis() - callStart;
            long inDelta = (usage == null ? 0 : usage.promptTokens()) - beforeIn;
            long outDelta = (usage == null ? 0 : usage.completionTokens()) - beforeOut;
            aiCallListener.onSuccess(ctx, finishReasonOf(resp), inDelta, outDelta, callMs);
            logRoundUsage("self", round, inDelta, outDelta, callMs);

            AssistantMessage out = resp.getResult().getOutput();
            // ① assistant 消息（含 toolCalls）必须入历史，否则下一轮模型看不到自己调过工具
            messages.add(out);
            if (out.getText() != null && !out.getText().isBlank() && description.get().isBlank()) {
                description.set(out.getText());
            }
            // ② 终止条件：模型不再请求工具
            if (!out.hasToolCalls()) {
                break;
            }
            // ③ 执行工具并成对写回（响应 id 必须与 ToolCall 一致）
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
                ToolCallback callback = byName.get(tc.name());
                String result;
                try {
                    result = callback == null ? "未知工具: " + tc.name() : callback.call(tc.arguments());
                } catch (RuntimeException e) {
                    // 与框架行为一致：工具异常吞成"工具结果"喂回模型，不向上抛（development-notes #68）
                    result = "工具执行失败: " + e.getMessage();
                }
                responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
            if (finished.get()) {
                break;
            }
        }
    }

    /**
     * 单轮模型调用结束后的用量打点（S5-3 ③）：本轮 prompt / completion 分列。
     *
     * <p>与轮次开始前的累计打点配合，可同时得到「本轮用量」与「累计用量」。之所以必须单列 prompt：
     * ③ 的判据是「末轮 promptTokens ÷ 首轮 promptTokens」，而轮次<b>开始前</b>的打点记的是"进入本轮时"
     * 的累计值 —— 末轮自身的用量在那一行里根本不存在（循环因收敛而结束，不会再打印下一行），只能在这里记。
     */
    private static void logRoundUsage(String mode, int round, long promptDelta, long completionDelta, long callMs) {
        log.info("[agent] round={} 模式={} 本轮prompt={} 本轮completion={} total={} 调用耗时={}ms",
                round, mode, promptDelta, completionDelta, promptDelta + completionDelta, callMs);
    }

    private String finishReasonOf(ChatResponse resp) {
        try {
            if (resp == null || resp.getResult() == null || resp.getResult().getMetadata() == null) {
                return null;
            }
            return resp.getResult().getMetadata().getFinishReason();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 流式调用并聚合：逐 chunk 累积文本内容、收集模型思考过程（reasoningContent，存于消息 metadata）、
     * 保留最后一份 usage 元信息，返回与 call() 等价的 ChatResponse（供后续判断 hasToolCalls/usage）。
     * 框架会在流内部自动执行工具回调（writeFile/finish），reasoning 增量通过 reasoningSink 实时转发。
     */
    private ChatResponse streamAndAggregate(Prompt prompt, Consumer<String> reasoningSink) {
        StringBuilder text = new StringBuilder();
        AtomicReference<ChatResponseMetadata> metaRef = new AtomicReference<>();
        AtomicReference<ChatGenerationMetadata> genMetaRef = new AtomicReference<>();
        // [0]=reasoning 字符数 [1]=正文 字符数
        long[] stats = new long[2];
        long start = System.nanoTime();
        Consumer<String> countingSink = reasoningSink == null ? null
                : (r -> {
                    stats[0] += r.length();
                    reasoningSink.accept(r);
                });
        chatModel.stream(prompt)
                .doOnNext(chunk -> {
                    Generation gen = chunk.getResult();
                    if (gen != null) {
                        AssistantMessage out = gen.getOutput();
                        if (out != null) {
                            // 只在非工具调用块追加文本，避免把工具参数增量拼进 content
                            if (!out.hasToolCalls()) {
                                String t = out.getText();
                                if (t != null && !t.isEmpty()) {
                                    text.append(t);
                                    stats[1] += t.length();
                                }
                            }
                            // reasoningContent 存在消息 metadata（lambda$internalStream$5 写入）
                            if (countingSink != null && out.getMetadata() != null) {
                                Object reasoning = out.getMetadata().get("reasoningContent");
                                if (reasoning != null) {
                                    String r = String.valueOf(reasoning);
                                    if (!r.isEmpty()) {
                                        countingSink.accept(r);
                                    }
                                }
                            }
                            genMetaRef.set(gen.getMetadata());
                        }
                    }
                    if (chunk.getMetadata() != null) {
                        metaRef.set(chunk.getMetadata());
                    }
                })
                .collectList()
                .block();
        log.info("[agent] 单次流式调用完成：reasoning={}字符 text={}字符 耗时={}ms",
                stats[0], stats[1], (System.nanoTime() - start) / 1_000_000);
        AssistantMessage msg = AssistantMessage.builder().content(text.toString()).build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(msg, genMetaRef.get())))
                .metadata(metaRef.get() == null ? ChatResponseMetadata.builder().build() : metaRef.get())
                .build();
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
     * 列出工程代码文件相对路径清单（不含内容，供 agent 判断该读哪些文件）
     */
    private String buildFileIndex(CodeGenTypeEnum type, Long appId) {
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        java.io.File root = new java.io.File(projectDir);
        List<java.io.File> codeFiles = projectCodeScanner.collectCodeFiles(root);
        StringBuilder sb = new StringBuilder();
        for (java.io.File f : codeFiles) {
            sb.append("- ").append(projectCodeScanner.relativePath(root, f)).append("\n");
        }
        return sb.length() == 0 ? "（暂无文件）" : sb.toString();
    }

    /**
     * 拼装工程相关文件内容（供修复 agent 参考），排除 node_modules/dist 和超大文件
     */
    private String buildFilesContext(Long appId) {
        List<VueFileResult> files = codeFileWriteService.readVueProject(
                codeFileWriteService.getProjectDir(CodeGenTypeEnum.VUE, appId));
        StringBuilder sb = new StringBuilder();
        for (VueFileResult f : files) {
            String content = f.getContent();
            if (content != null && content.length() > 8000) {
                sb.append("===== ").append(f.getPath()).append(" (内容过长，截断) =====\n")
                        .append(content, 0, 8000).append("\n\n");
            } else {
                sb.append("===== ").append(f.getPath()).append(" =====\n")
                        .append(content).append("\n\n");
            }
        }
        return sb.toString();
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
