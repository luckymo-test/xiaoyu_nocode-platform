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
 * 通用代码生成 Agent：通过手动 tool-calling loop 逐文件生成 html / multi_file / vue 三种工程。
 * 每轮让模型调用 writeFile 工具写一个文件（需要图片时先调 searchImage 获取 COS 图片 URL），
 * 写完再调用 finish 结束，突破单次输出 token 上限，且能逐文件回调进度（供 SSE 推送前端）。
 * 不使用 ChatClient 的自动工具循环，改用 ChatModel.call 手动控制，以便拿到逐文件进度。
 */
@Slf4j
@Component
public class VueProjectAgent {

    /**
     * 单次生成最大工具调用轮数（一个文件一轮），防止模型不收敛无限循环
     */
    private static final int MAX_STEPS = 20;

    private final ChatModel chatModel;
    private final String singleHtmlPrompt;
    private final String multiFilePrompt;
    private final String vuePrompt;
    private final String vueFixPrompt;
    private final String modifyPrompt;
    private final String renderFixPrompt;
    private final CodeFileWriteService codeFileWriteService;
    private final AgentMemoryService agentMemoryService;
    private final AppSpecService appSpecService;
    private final GenerationTaskRegistry taskRegistry;
    private final ManualEditService manualEditService;
    private final com.mofanyi.mapper.AppImageMapper appImageMapper;
    private final SiteImageService siteImageService;
    private final WriteFileTool writeFileTool;
    private final ReadFileTool readFileTool;
    private final SearchImageTool searchImageTool;
    private final DiagramTool diagramTool;
    private final FinishTool finishTool;
    private final AiCallListener aiCallListener;

    public VueProjectAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                           String singleHtmlPrompt,
                           String multiFilePrompt,
                           @Qualifier("vuePrompt") String vuePrompt,
                           @Qualifier("vueFixPrompt") String vueFixPrompt,
                           @Qualifier("modifyPrompt") String modifyPrompt,
                           @Qualifier("renderFixPrompt") String renderFixPrompt,
                           CodeFileWriteService codeFileWriteService,
                           AgentMemoryService agentMemoryService,
                           AppSpecService appSpecService,
                           GenerationTaskRegistry taskRegistry,
                           ManualEditService manualEditService,
                           com.mofanyi.mapper.AppImageMapper appImageMapper,
                           SiteImageService siteImageService,
                           WriteFileTool writeFileTool,
                           ReadFileTool readFileTool,
                           SearchImageTool searchImageTool,
                           DiagramTool diagramTool,
                           FinishTool finishTool,
                           AiCallListener aiCallListener) {
        this.chatModel = chatModel;
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.vuePrompt = vuePrompt;
        this.vueFixPrompt = vueFixPrompt;
        this.modifyPrompt = modifyPrompt;
        this.renderFixPrompt = renderFixPrompt;
        this.codeFileWriteService = codeFileWriteService;
        this.agentMemoryService = agentMemoryService;
        this.appSpecService = appSpecService;
        this.taskRegistry = taskRegistry;
        this.manualEditService = manualEditService;
        this.appImageMapper = appImageMapper;
        this.siteImageService = siteImageService;
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
        messages.add(new SystemMessage(promptForType(type)));
        // 注入"应用现状"（规格摘要 + 代码库索引）：续接任务时模型需要的是应用现状，而非聊天原文
        String specSection = appSpecService.buildSpecSection(type, appId);
        if (StrUtil.isNotBlank(specSection)) {
            messages.add(new SystemMessage(specSection));
        }
        // 注入对话记忆作为前置上下文（优先 Redis 热窗口，miss 时从 MySQL 冷启动回灌）
        messages.addAll(agentMemoryService.loadContext(appId, userMessage));
        messages.add(new UserMessage(userMessage));

        // [promptTokens, completionTokens] 累加器，runToolLoop 各轮模型调用后写入
        long[] usage = new long[2];
        // 生成前快照用户手动修改：重新生成是整文件覆盖，会把 @user-edit 标记块一起抹掉（plan-refactor 6.2）
        ManualEditService.UserEditSnapshot userEdits = manualEditService.snapshot(type, appId);
        try {
            runToolLoop(type, appId, messages, files, finished, description, usage, ctx, listener);
            // 强制补图：模型可能漏调 searchImage，导致页面缺图。扫描代码中的空图片字段/占位图并填真实图。
            autoFillImages(type, appId, userMessage);
            // 生成完成后扫描项目代码，把嵌入的图片 URL 落库（含补图后的真实 URL）
            scanAndSaveImages(type, appId);
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
        result.setUsage(new TokenUsage(usage[0], usage[1]));
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
     * 强制自动补图：模型生成时可能不调用 searchImage，导致页面出现空 src、占位图或空 image 字段。
     * 这里扫描代码文件，把缺图位置替换为 Pexels 搜索并上传 COS 的真实图片 URL。
     * 只处理图片语义字段（image/img/src/url/cover）的空值，不动 emoji/渐变/表单占位，避免破坏页面结构。
     */
    private void autoFillImages(CodeGenTypeEnum type, Long appId, String userMessage) {
        try {
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            java.io.File root = new java.io.File(projectDir);
            if (!root.isDirectory()) {
                return;
            }
            // 收集代码文件并逐个处理
            java.util.List<java.io.File> codeFiles = new java.util.ArrayList<>();
            collectCodeFiles(root, codeFiles);
            String query = extractImageQuery(userMessage);
            int filledCount = 0;
            final int MAX_FILL = 8; // 单页最多补图数，防止过量
            boolean injectedEmojiImg = false; // 是否把 emoji 换成了 <img>，需要补 CSS

            for (java.io.File f : codeFiles) {
                if (filledCount >= MAX_FILL) {
                    break;
                }
                String path = relativePath(root, f);
                String content = cn.hutool.core.io.FileUtil.readUtf8String(f);
                String newContent = fillImagesInContent(content, path, appId, query, filledCount, MAX_FILL);
                if (newContent != null && !newContent.equals(content)) {
                    // 检测本次是否产生了 <img class="autofill-img">（emoji/icon 字段替换）
                    if (newContent.contains("autofill-img")) {
                        injectedEmojiImg = true;
                    }
                    codeFileWriteService.saveProjectFile(type, appId, path, newContent);
                    // 重新统计已补图数（用 COS URL 出现次数）
                    filledCount = countCosUrls(projectDir);
                }
            }
            // 若把 emoji/icon 换成了内嵌 <img>，确保 CSS 里有点亮样式，否则图片会撑破布局
            if (injectedEmojiImg) {
                ensureAutofillCss(type, appId, root);
            }
            if (filledCount > 0) {
                log.info("[补图] 已填充 {} 张真实图片，appId={}", filledCount, appId);
            }
        } catch (RuntimeException e) {
            log.warn("[补图] 自动补图失败，appId={}", appId, e);
        }
    }

    /**
     * 给生成的 css 文件追加 .autofill-img 通用样式（若尚未存在），保证补图 img 不撑破布局
     */
    private void ensureAutofillCss(CodeGenTypeEnum type, Long appId, java.io.File root) {
        try {
            String cssPath = "css/styles.css";
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            String css = codeFileWriteService.readFile(projectDir, cssPath);
            if (css == null) {
                return;
            }
            if (css.contains(".autofill-img")) {
                return; // 已有样式
            }
            String autofillCss = "\n/* 自动补图通用样式 */\n"
                    + ".autofill-img{width:100%;height:140px;object-fit:cover;display:block;"
                    + "border-radius:inherit;}\n";
            codeFileWriteService.saveProjectFile(type, appId, cssPath, css + autofillCss);
            log.info("[补图] 已注入 autofill-img CSS，appId={}", appId);
        } catch (RuntimeException e) {
            log.warn("[补图] 注入 CSS 失败，appId={}", appId, e);
        }
    }

    /**
     * 处理单个代码文件内容中的缺图位置，返回替换后的内容；无改动返回原内容。
     */
    private String fillImagesInContent(String content, String path, Long appId, String query,
                                       int alreadyFilled, int maxFill) {
        String result = content;
        int[] filled = {alreadyFilled};
        // ① JS 数据数组里的图片字段：image/img/src/url/cover 为空/占位，emoji/icon 为 emoji 字符 → 都替换为真实图
        //    替换为内嵌 <img> 标签（渲染用 innerHTML 会解析成图片），CSS 由 .autofill-img 兜底
        java.util.regex.Pattern fieldPattern = java.util.regex.Pattern.compile(
                "(?i)(\\b(?:image|img|src|url|cover|emoji|icon)\\s*:\\s*)(['\"])((?:\"\"|''|#)|(?:https?://(?:picsum\\.photos|via\\.placeholder)[^'\"]*)|(?:(?:[^\\\\]|\\\\\\\\.)*?))\\2");
        // ② HTML <img src=""> 或占位
        java.util.regex.Pattern emptyImg = java.util.regex.Pattern.compile(
                "(?i)(<img[^>]*?\\bsrc\\s*=\\s*)(['\"])(?:(?:\"\"|''|#)|(?:https?://(?:picsum\\.photos|via\\.placeholder)[^'\"]*))?\\2([^>]*?>)");

        // 优先处理数据字段（image/emoji 等）
        java.util.regex.Matcher m1 = fieldPattern.matcher(result);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find() && filled[0] < maxFill) {
            String field = m1.group(1).trim().toLowerCase();
            String rawValue = m1.group(3);
            // 判断是否需替换：空值/占位服务/纯 emoji（非 URL）
            boolean isEmptyValue = rawValue == null || rawValue.isBlank()
                    || "\"\"".equals(rawValue) || "''".equals(rawValue) || "#".equals(rawValue);
            boolean isPlaceholder = rawValue != null
                    && (rawValue.contains("picsum") || rawValue.contains("via.placeholder"));
            boolean isEmojiValue = rawValue != null
                    && (field.contains("emoji") || field.contains("icon"))
                    && !rawValue.startsWith("http");
            if (!isEmptyValue && !isPlaceholder && !isEmojiValue) {
                continue; // 已有真实值或非图片场景，跳过
            }
            String replacement = siteImageService.fetchOneImage(appId, query);
            if (replacement == null) {
                continue;
            }
            // emoji/icon 字段用内嵌 img 标签替换；image/src/url/cover 用纯 URL
            String newValue;
            if (field.contains("emoji") || field.contains("icon")) {
                newValue = "<img src=\"" + replacement + "\" class=\"autofill-img\" alt=\"\">";
            } else {
                newValue = replacement;
            }
            m1.appendReplacement(sb1, java.util.regex.Matcher.quoteReplacement(
                    m1.group(1) + m1.group(2) + newValue + m1.group(2)));
            filled[0]++;
        }
        m1.appendTail(sb1);
        result = sb1.toString();

        // 再处理 <img src=""> 空 src
        java.util.regex.Matcher m2 = emptyImg.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find() && filled[0] < maxFill) {
            String replacement = siteImageService.fetchOneImage(appId, query);
            if (replacement == null) {
                continue;
            }
            m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement(
                    m2.group(1) + m2.group(2) + replacement + m2.group(2) + m2.group(3)));
            filled[0]++;
        }
        m2.appendTail(sb2);
        result = sb2.toString();

        return filled[0] > alreadyFilled ? result : content;
    }

    /**
     * 统计项目目录中已生成的 COS 图片 URL 数量
     */
    private int countCosUrls(String projectDir) {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        collectCodeFiles(new java.io.File(projectDir), files);
        int count = 0;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("app-image/");
        for (java.io.File f : files) {
            try {
                String content = cn.hutool.core.io.FileUtil.readUtf8String(f);
                java.util.regex.Matcher m = p.matcher(content);
                while (m.find()) {
                    count++;
                }
            } catch (Exception ignore) {
            }
        }
        return count;
    }

    /**
     * 从用户需求中提取 Pexels 搜索关键词：去掉"生成/一个/页面/需要/带/的"等无意义词，保留内容词
     */
    private String extractImageQuery(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "beautiful landscape";
        }
        // 去掉常见语气词和无意义词，保留主题词
        String cleaned = userMessage
                .replaceAll("[，。！？,.!?、\s]+", " ")
                .replaceAll("(生成|制作|创建|设计|一个|一款|页面|网页|网站|首页|需要|希望|要求|展示|提供|带|用|的|风格|简洁|美观|大气)", " ")
                .trim();
        if (cleaned.isBlank() || cleaned.length() < 2) {
            return "beautiful landscape";
        }
        // 取前几个词作为 query（太长会稀释语义）
        String[] words = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(words.length, 4); i++) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(words[i]);
        }
        return sb.toString();
    }

    /**
     * 计算文件相对项目根路径（复用 CodeFileWriteService 的路径语义）
     */
    private String relativePath(java.io.File root, java.io.File file) {
        String rel = root.toPath().toAbsolutePath().relativize(file.toPath().toAbsolutePath())
                .toString().replace('\\', '/');
        return rel.startsWith("/") ? rel.substring(1) : rel;
    }

    /**
     * 扫描项目代码文件中的图片 URL（<img src>、background-image、数据数组等），写入 app_image。
     * 与 searchImage 落库互补，保证生成的页面图片可靠入库。
     */
    private void scanAndSaveImages(CodeGenTypeEnum type, Long appId) {
        try {
            java.util.Set<String> urls = new java.util.LinkedHashSet<>();
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            java.io.File root = new java.io.File(projectDir);
            if (!root.isDirectory()) {
                log.info("[scan] 项目目录不存在，跳过，dir={}", projectDir);
                return;
            }
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "https?://[^\"'\\s)\\]}]+?\\.(?:jpg|jpeg|png|webp|gif)(?:\\?[^\"'\\s)]*)?");

            // 递归收集代码文件（排除 node_modules/dist）
            java.util.List<java.io.File> codeFiles = new java.util.ArrayList<>();
            collectCodeFiles(root, codeFiles);
            for (java.io.File f : codeFiles) {
                try {
                    String content = cn.hutool.core.io.FileUtil.readUtf8String(f);
                    java.util.regex.Matcher m = pattern.matcher(content);
                    while (m.find()) {
                        urls.add(m.group().replace("\\/", "/"));
                    }
                } catch (Exception ignore) {
                    // 单文件读取失败不影响整体
                }
            }
            // 只保留平台 COS 图（app-image 前缀）或其他可商用外链，这里都入库
            log.info("[scan] 扫描到 {} 个图片 URL，appId={}", urls.size(), appId);
            int order = 1;
            for (String url : urls) {
                if (url.startsWith("http")) {
                    siteImageService.saveAppImage(appId, url);
                    order++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("扫描落库图片失败，appId={}", appId, e);
        }
    }

    private void collectCodeFiles(java.io.File dir, java.util.List<java.io.File> out) {
        java.io.File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (java.io.File child : children) {
            if (child.isDirectory()) {
                if ("node_modules".equals(child.getName()) || "dist".equals(child.getName())) {
                    continue;
                }
                collectCodeFiles(child, out);
            } else {
                String name = child.getName().toLowerCase();
                if (name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js")
                        || name.endsWith(".vue") || name.endsWith(".json")) {
                    out.add(child);
                }
            }
        }
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
        messages.add(new SystemMessage(vueFixPrompt));
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
        messages.add(new SystemMessage(renderFixPrompt));
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
                                                   String instruction, long[] usage, AiCallContext ctx,
                                                   VueProgressListener listener) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(modifyPrompt));
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
                             AtomicBoolean finished, AtomicReference<String> description, long[] usage,
                             AiCallContext ctx, VueProgressListener listener) {
        List<ToolCallback> toolCallbacks = List.of(
                writeFileTool.build(type, appId, files, listener),
                readFileTool.build(type, appId),
                searchImageTool.build(appId),
                diagramTool.build(appId),
                finishTool.build(finished, description));
        var options = DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).build();

        // 连续异常计数：模型返回被截断（工具参数 JSON 不完整）等可恢复错误时重试，连续多次失败才终止
        int consecutiveFailures = 0;
        long loopStart = System.currentTimeMillis();
        int stepCount = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            stepCount++;
            ChatResponse resp;
            // 每次模型调用的耗时与本次 token 增量（用于可观测性）
            long callStart = System.currentTimeMillis();
            long beforeIn = usage == null ? 0 : usage[0];
            long beforeOut = usage == null ? 0 : usage[1];
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
            accumulateUsage(resp, usage);
            // 可观测性：记录本次调用（成功）
            long callMs = System.currentTimeMillis() - callStart;
            long inDelta = (usage == null ? 0 : usage[0]) - beforeIn;
            long outDelta = (usage == null ? 0 : usage[1]) - beforeOut;
            aiCallListener.onSuccess(ctx, finishReasonOf(resp), inDelta, outDelta, callMs);
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
     * 累加单轮模型响应的 token 用量到 usage[0]=prompt、usage[1]=completion
     */
    private void accumulateUsage(ChatResponse resp, long[] usage) {
        if (usage == null || resp == null || resp.getMetadata() == null) {
            return;
        }
        var usageMeta = resp.getMetadata().getUsage();
        if (usageMeta == null) {
            return;
        }
        if (usageMeta.getPromptTokens() != null) {
            usage[0] += usageMeta.getPromptTokens();
        }
        if (usageMeta.getCompletionTokens() != null) {
            usage[1] += usageMeta.getCompletionTokens();
        }
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
     * 按类型选择 system prompt
     */
    private String promptForType(CodeGenTypeEnum type) {
        return switch (type) {
            case HTML -> singleHtmlPrompt;
            case MULTI_FILE -> multiFilePrompt;
            case VUE -> vuePrompt;
        };
    }

    /**
     * 列出工程代码文件相对路径清单（不含内容，供 agent 判断该读哪些文件）
     */
    private String buildFileIndex(CodeGenTypeEnum type, Long appId) {
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        List<java.io.File> codeFiles = new java.util.ArrayList<>();
        collectCodeFiles(new java.io.File(projectDir), codeFiles);
        StringBuilder sb = new StringBuilder();
        for (java.io.File f : codeFiles) {
            sb.append("- ").append(relativePath(new java.io.File(projectDir), f)).append("\n");
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
     * 单次生成的 token 用量（模型 usage 累加）
     */
    public record TokenUsage(long promptTokens, long completionTokens) {

        public long totalTokens() {
            return promptTokens + completionTokens;
        }
    }

    /**
     * 通用生成结果：vue 类型含文件清单；html/multi_file 由门面从项目目录读取
     */
    public static class GenerateResult {
        private String description;
        private VueProjectResult vueResult;
        private TokenUsage usage;

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

        public TokenUsage getUsage() {
            return usage;
        }

        public void setUsage(TokenUsage usage) {
            this.usage = usage;
        }
    }
}
