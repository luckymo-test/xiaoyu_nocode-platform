package com.mofanyi.ai;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mofanyi.ai.model.VueFileResult;
import com.mofanyi.ai.model.VueProjectResult;
import com.mofanyi.config.CosClientConfig;
import com.mofanyi.core.CodeFileWriteService;
import com.mofanyi.core.PexelsClient;
import com.mofanyi.exception.ErrorCode;
import com.mofanyi.exception.ThrowUtils;
import com.mofanyi.manager.CosManager;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
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
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel chatModel;
    private final String singleHtmlPrompt;
    private final String multiFilePrompt;
    private final String vuePrompt;
    private final String vueFixPrompt;
    private final String modifyPrompt;
    private final CodeFileWriteService codeFileWriteService;
    private final ChatMemory chatMemory;
    private final PexelsClient pexelsClient;
    private final CosManager cosManager;
    private final CosClientConfig cosClientConfig;
    private final com.mofanyi.mapper.AppImageMapper appImageMapper;

    public VueProjectAgent(@Qualifier("openAiChatModel") ChatModel chatModel,
                           String singleHtmlPrompt,
                           String multiFilePrompt,
                           @Qualifier("vuePrompt") String vuePrompt,
                           @Qualifier("vueFixPrompt") String vueFixPrompt,
                           @Qualifier("modifyPrompt") String modifyPrompt,
                           CodeFileWriteService codeFileWriteService,
                           ChatMemory chatMemory,
                           PexelsClient pexelsClient,
                           CosManager cosManager,
                           CosClientConfig cosClientConfig,
                           com.mofanyi.mapper.AppImageMapper appImageMapper) {
        this.chatModel = chatModel;
        this.singleHtmlPrompt = singleHtmlPrompt;
        this.multiFilePrompt = multiFilePrompt;
        this.vuePrompt = vuePrompt;
        this.vueFixPrompt = vueFixPrompt;
        this.modifyPrompt = modifyPrompt;
        this.codeFileWriteService = codeFileWriteService;
        this.chatMemory = chatMemory;
        this.pexelsClient = pexelsClient;
        this.cosManager = cosManager;
        this.cosClientConfig = cosClientConfig;
        this.appImageMapper = appImageMapper;
    }

    /**
     * 逐文件回调进度：每写入一个文件触发一次，携带文件路径与完整内容（供前端流式展示代码）
     */
    public interface VueProgressListener {
        void onFileWritten(String path, int size, String content);

        /**
         * 模型思考过程增量（reasoning_content），默认空实现；前端据此流式展示"思考过程"
         */
        default void onReasoning(String reasoning) {
        }
    }

    /**
     * 生成 Vue3 工程（兼容原调用）：委托通用生成，按 vue 类型执行。
     */
    public VueProjectResult generate(Long appId, String userMessage, VueProgressListener listener) {
        GenerateResult result = generate(CodeGenTypeEnum.VUE, appId, userMessage, listener);
        return result.getVueResult();
    }

    /**
     * 通用生成入口：按类型逐文件生成工程。
     *
     * @param type        代码生成类型（html / multi_file / vue）
     * @param appId       应用 id
     * @param userMessage 用户需求
     * @param listener    进度回调，可为 null
     * @return 按类型组装的结果
     */
    public GenerateResult generate(CodeGenTypeEnum type, Long appId, String userMessage, VueProgressListener listener) {
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
        // 注入对话记忆作为前置上下文
        messages.addAll(chatMemory.get(String.valueOf(appId)));
        messages.add(new UserMessage(userMessage));

        // [promptTokens, completionTokens] 累加器，runToolLoop 各轮模型调用后写入
        long[] usage = new long[2];
        runToolLoop(type, appId, messages, files, finished, description, usage, listener);

        // 强制补图：模型可能漏调 searchImage，导致页面缺图。扫描代码中的空图片字段/占位图并填真实图。
        autoFillImages(type, appId, userMessage);
        // 生成完成后扫描项目代码，把嵌入的图片 URL 落库（含补图后的真实 URL）
        scanAndSaveImages(type, appId);

        GenerateResult result = assembleResult(type, appId, files, description.get());
        result.setUsage(new TokenUsage(usage[0], usage[1]));
        return result;
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
            String replacement = fetchOneImage(appId, query);
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
            String replacement = fetchOneImage(appId, query);
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
     * 从 Pexels 搜一张图并上传 COS，返回 COS URL；失败返回 null
     */
    private String fetchOneImage(Long appId, String query) {
        try {
            java.util.List<PexelsClient.PexelsImage> images = pexelsClient.searchImages(query, null);
            if (images == null || images.isEmpty()) {
                return null;
            }
            String url = downloadAndUpload(appId, images.get(0).getUrl());
            if (url == null) {
                return null;
            }
            saveAppImage(appId, url);
            return url;
        } catch (Exception e) {
            log.warn("[补图] 获取图片失败，appId={}", appId, e);
            return null;
        }
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
                    saveAppImage(appId, url);
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
    public void fixBuildError(Long appId, String errorLog, VueProgressListener listener) {
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

        runToolLoop(CodeGenTypeEnum.VUE, appId, messages, files, finished, description, null, listener);
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
                                                   String instruction, long[] usage, VueProgressListener listener) {
        ThrowUtils.throwIf(type == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改意图不能为空");

        Map<String, String> files = new LinkedHashMap<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> description = new AtomicReference<>("");
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(modifyPrompt));
        String ctx = StrUtil.isBlank(elementContext) ? "（无具体元素，基于整体需求修改）" : elementContext;
        messages.add(new UserMessage("用户想修改当前页面。\n\n【目标元素上下文】\n" + ctx
                + "\n\n【用户修改意图】\n" + instruction
                + "\n\n【当前工程文件清单】\n" + buildFileIndex(type, appId)
                + "\n\n请先 readFile 读取涉及的文件，定位目标元素对应代码段，最小改动后用 writeFile 覆盖，改完调用 finish。"));
        runToolLoop(type, appId, messages, files, finished, description, usage, listener);
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
            validatePath(e.getKey());
            codeFileWriteService.saveProjectFile(type, appId, e.getKey(), e.getValue());
        }
    }

    /**
     * 手动 tool-calling 循环：每轮把历史消息发给模型，若返回工具调用则执行并拼回结果继续，
     * 直到模型不再调工具或达最大轮数。
     */
    private void runToolLoop(CodeGenTypeEnum type, Long appId, List<Message> messages, Map<String, String> files,
                             AtomicBoolean finished, AtomicReference<String> description, long[] usage,
                             VueProgressListener listener) {
        List<ToolCallback> toolCallbacks = List.of(
                buildWriteFileTool(type, appId, files, listener),
                buildReadFileTool(type, appId),
                buildSearchImageTool(appId),
                buildFinishTool(finished, description));
        var options = DefaultToolCallingChatOptions.builder().toolCallbacks(toolCallbacks).build();

        // 连续异常计数：模型返回被截断（工具参数 JSON 不完整）等可恢复错误时重试，连续多次失败才终止
        int consecutiveFailures = 0;
        long loopStart = System.currentTimeMillis();
        int stepCount = 0;
        for (int step = 0; step < MAX_STEPS; step++) {
            stepCount++;
            ChatResponse resp;
            try {
                // 用流式调用聚合，以便拿到模型思考过程（reasoning_content）增量
                resp = streamAndAggregate(new Prompt(messages, options),
                        listener == null ? null : listener::onReasoning);
            } catch (RuntimeException e) {
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
            accumulateUsage(resp, usage);
            AssistantMessage output = resp.getResult().getOutput();
            if (!output.hasToolCalls()) {
                // 模型直接返回文本（未调工具）→ 结束
                if (description.get().isBlank()) {
                    description.set(output.getText());
                }
                break;
            }
            messages.add(output);
            List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();
            for (AssistantMessage.ToolCall tc : output.getToolCalls()) {
                String result;
                try {
                    log.info("[agent] step={} tool={} args={}", step, tc.name(),
                            tc.arguments() == null ? "null" : tc.arguments().substring(0,
                                    Math.min(tc.arguments().length(), 200)));
                    result = executeTool(tc.name(), tc.arguments(), type, appId, files, finished, description, listener);
                } catch (RuntimeException e) {
                    log.warn("[agent] tool exec error, tool={}", tc.name(), e);
                    result = "执行失败: " + e.getMessage();
                }
                toolResponses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result));
            }
            messages.add(ToolResponseMessage.builder().responses(toolResponses).build());
            if (finished.get()) {
                break;
            }
        }
        log.info("[agent] tool-loop 结束：外层 step={}，写文件数={}，总耗时={}ms",
                stepCount, files.size(), System.currentTimeMillis() - loopStart);
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
     * 构建 writeFile 工具：写文件到指定类型工程目录并记录进度
     */
    private ToolCallback buildWriteFileTool(CodeGenTypeEnum type, Long appId, Map<String, String> files,
                                            VueProgressListener listener) {
        return FunctionToolCallback.builder("writeFile", (WriteFileRequest req) -> {
            validatePath(req.path());
            if (req.content() == null) {
                return "内容为空，未写入";
            }
            codeFileWriteService.saveProjectFile(type, appId, req.path(), req.content());
            files.put(req.path(), req.content());
            if (listener != null) {
                listener.onFileWritten(req.path(), req.content().length(), req.content());
            }
            return "已写入 " + req.path() + "（" + req.content().length() + " 字符）";
        })
                .description("向工程写入一个文件。path 为相对路径，content 为文件完整内容。一次只写一个文件，写完再写下一个。")
                .inputType(WriteFileRequest.class)
                .build();
    }

    /**
     * 构建 readFile 工具：读取工程某文件的完整内容（供修改/自检按需读精确源码，避免全量塞入上下文）
     */
    private ToolCallback buildReadFileTool(CodeGenTypeEnum type, Long appId) {
        return FunctionToolCallback.builder("readFile", (ReadFileRequest req) -> {
            validatePath(req.path());
            String content = codeFileWriteService.readFile(
                    codeFileWriteService.getProjectDir(type, appId), req.path());
            return content == null
                    ? "文件不存在: " + req.path() + "（请先看文件清单确认正确相对路径）"
                    : "【" + req.path() + "】\n" + content;
        })
                .description("读取工程中某个源文件的完整内容。path 为相对工程根目录的路径（如 index.html、css/styles.css、js/app.js）。修改前应先读取目标文件再改动。")
                .inputType(ReadFileRequest.class)
                .build();
    }

    /**
     * 构建 searchImage 工具：按语义描述从 Pexels 图库搜索图片，下载并上传 COS，返回可直接嵌入代码的 URL
     */
    private ToolCallback buildSearchImageTool(Long appId) {
        return FunctionToolCallback.builder("searchImage", (SearchImageRequest req) -> {
            if (req.query() == null || req.query().isBlank()) {
                return "查询描述不能为空";
            }
            return searchAndUploadImages(appId, req.query(), req.orientation());
        })
                .description("需要为页面配图时调用本工具。query 为图片的语义描述（如\"科技感蓝紫色渐变背景\"），orientation 可选（landscape 横向 / portrait 纵向 / square 方形）。调用后返回若干张已上传 COS 的图片 URL，直接在代码的 <img src> 或 CSS background-image 中引用即可。")
                .inputType(SearchImageRequest.class)
                .build();
    }

    /**
     * 构建 finish 工具：结束生成
     */
    private ToolCallback buildFinishTool(AtomicBoolean finished, AtomicReference<String> description) {
        return FunctionToolCallback.builder("finish", (FinishRequest req) -> {
            description.set(req.description() == null ? "" : req.description());
            finished.set(true);
            return "已结束生成";
        })
                .description("所有文件写入完成后调用本工具结束生成。description 为工程整体概述（2~3 句话说明结构与功能）。")
                .inputType(FinishRequest.class)
                .build();
    }

    /**
     * 按工具名手动执行（解析 arguments JSON）
     */
    private String executeTool(String name, String arguments, CodeGenTypeEnum type, Long appId, Map<String, String> files,
                               AtomicBoolean finished, AtomicReference<String> description,
                               VueProgressListener listener) {
        if ("writeFile".equals(name)) {
            try {
                WriteFileRequest req = MAPPER.readValue(arguments, WriteFileRequest.class);
                validatePath(req.path());
                if (req.content() == null) {
                    return "内容为空，未写入";
                }
                codeFileWriteService.saveProjectFile(type, appId, req.path(), req.content());
                files.put(req.path(), req.content());
                if (listener != null) {
                    listener.onFileWritten(req.path(), req.content().length(), req.content());
                }
                return "已写入 " + req.path() + "（" + req.content().length() + " 字符）";
            } catch (Exception e) {
                return "解析参数失败: " + e.getMessage();
            }
        }
        if ("readFile".equals(name)) {
            try {
                ReadFileRequest req = MAPPER.readValue(arguments, ReadFileRequest.class);
                validatePath(req.path());
                String content = codeFileWriteService.readFile(
                        codeFileWriteService.getProjectDir(type, appId), req.path());
                return content == null ? "文件不存在: " + req.path() : content;
            } catch (Exception e) {
                return "解析参数失败: " + e.getMessage();
            }
        }
        if ("searchImage".equals(name)) {
            try {
                SearchImageRequest req = MAPPER.readValue(arguments, SearchImageRequest.class);
                if (req.query() == null || req.query().isBlank()) {
                    return "查询描述不能为空";
                }
                return searchAndUploadImages(appId, req.query(), req.orientation());
            } catch (Exception e) {
                return "解析参数失败: " + e.getMessage();
            }
        }
        if ("finish".equals(name)) {
            try {
                FinishRequest req = MAPPER.readValue(arguments, FinishRequest.class);
                description.set(req.description() == null ? "" : req.description());
            } catch (Exception e) {
                description.set(arguments == null ? "" : arguments);
            }
            finished.set(true);
            return "已结束生成";
        }
        return "未知工具: " + name;
    }

    /**
     * 搜索图片 → 逐张下载并上传 COS → 落库 app_image → 返回 COS URL 列表文本（含编号与尺寸，供模型挑选）
     */
    private String searchAndUploadImages(Long appId, String query, String orientation) {
        log.info("[searchImage] 被调用 query={} orientation={}", query, orientation);
        List<PexelsClient.PexelsImage> images = pexelsClient.searchImages(query, orientation);
        if (images.isEmpty()) {
            log.warn("[searchImage] 无结果（Pexels 空/失败），query={}", query);
            return "未找到匹配的图片，请换一个描述重新搜索，或不用图片直接用纯色/渐变背景";
        }
        StringBuilder sb = new StringBuilder("找到以下图片（可直接用 URL 嵌入代码，建议优先用列表中的 URL）：\n");
        int index = 1;
        for (PexelsClient.PexelsImage img : images) {
            try {
                String cosUrl = downloadAndUpload(appId, img.getUrl());
                saveAppImage(appId, cosUrl);
                sb.append(index).append(". URL=").append(cosUrl)
                        .append(" 尺寸=").append(img.getWidth()).append("x").append(img.getHeight())
                        .append("\n");
                index++;
            } catch (Exception e) {
                log.warn("图片上传 COS 失败，appId={}, url={}", appId, img.getUrl(), e);
                sb.append(index).append(". 该图上传失败，请跳过\n");
                index++;
            }
        }
        return sb.toString();
    }

    /**
     * 将上传成功的图片 URL 落库到 app_image（记录该应用用到的嵌入图）。
     * sortOrder 基于该应用已有记录数累加，多次 searchImage 调用时编号不重复。
     */
    private void saveAppImage(Long appId, String cosUrl) {
        try {
            long existing = appImageMapper.selectCountByQuery(
                    com.mybatisflex.core.query.QueryWrapper.create().eq("appId", appId));
            com.mofanyi.entity.AppImage appImage = com.mofanyi.entity.AppImage.builder()
                    .appId(appId)
                    .imageUrl(cosUrl)
                    .imageType("embed")
                    .sortOrder((int) existing + 1)
                    .build();
            appImageMapper.insert(appImage);
        } catch (RuntimeException e) {
            log.warn("图片落库 app_image 失败，appId={}, url={}", appId, cosUrl, e);
        }
    }

    /**
     * 下载图片到本地临时文件，上传 COS，返回 COS URL；上传后删除本地临时文件
     */
    private String downloadAndUpload(Long appId, String imageUrl) throws Exception {
        byte[] bytes = pexelsClient.downloadImage(imageUrl);
        String suffix = ".jpg";
        File tmpFile = File.createTempFile("appimg_", suffix);
        try {
            FileUtil.writeBytes(bytes, tmpFile);
            String key = "app-image/" + appId + "/" + UUID.randomUUID() + suffix;
            cosManager.putObject(key, tmpFile);
            return cosClientConfig.getHost() + "/" + key;
        } finally {
            FileUtil.del(tmpFile);
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
     * 校验文件路径安全：必须是相对路径，禁止绝对路径、路径穿越、盘符
     */
    private void validatePath(String path) {
        ThrowUtils.throwIf(path == null || path.isBlank(), ErrorCode.PARAMS_ERROR, "文件路径不能为空");
        ThrowUtils.throwIf(path.startsWith("/") || path.startsWith("\\") || path.matches("^[a-zA-Z]:.*"),
                ErrorCode.PARAMS_ERROR, "文件路径必须是相对路径");
        ThrowUtils.throwIf(path.contains(".."), ErrorCode.PARAMS_ERROR, "文件路径非法");
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
     * writeFile 工具入参
     */
    public record WriteFileRequest(String path, String content) {
    }

    /**
     * readFile 工具入参
     */
    public record ReadFileRequest(String path) {
    }

    /**
     * finish 工具入参
     */
    public record FinishRequest(String description) {
    }

    /**
     * searchImage 工具入参
     */
    public record SearchImageRequest(String query, String orientation) {
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
