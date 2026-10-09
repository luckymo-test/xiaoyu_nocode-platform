package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 生成后强制补图（plan-refactor 7.1 拆分第 S2 步，从 VueProjectAgent 抽出）。
 *
 * <p>模型生成时可能不调用 searchImage，导致页面出现空 src、占位图或空 image 字段。
 * 这里扫描代码文件，把缺图位置替换为真实图片 URL，并在需要时补一段通用 CSS。
 */
@Slf4j
@Component
public class ImageAutoFiller {

    /**
     * 单页最多补图数，防止过量
     */
    private static final int MAX_FILL = 8;

    /**
     * 缺图字段：image / img / src / url / cover 为空、占位服务、或 emoji/icon 为纯字符
     */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?i)(\\b(?:image|img|src|url|cover|emoji|icon)\\s*:\\s*)(['\"])((?:\"\"|'')|(?:#)"
                    + "|(?:https?://(?:picsum\\.photos|via\\.placeholder)[^'\"]*)"
                    + "|(?:(?:[^\\\\]|\\\\\\\\.)*?))\\2");

    /**
     * HTML 里 &lt;img src=""&gt; 或占位图
     */
    private static final Pattern EMPTY_IMG = Pattern.compile(
            "(?i)(<img[^>]*?\\bsrc\\s*=\\s*)(['\"])(?:(?:\"\"|''|#)"
                    + "|(?:https?://(?:picsum\\.photos|via\\.placeholder)[^'\"]*))?\\2([^>]*?>)");

    private final CodeFileWriteService codeFileWriteService;
    private final SiteImageService siteImageService;
    private final ProjectCodeScanner projectCodeScanner;

    public ImageAutoFiller(CodeFileWriteService codeFileWriteService,
                           SiteImageService siteImageService,
                           ProjectCodeScanner projectCodeScanner) {
        this.codeFileWriteService = codeFileWriteService;
        this.siteImageService = siteImageService;
        this.projectCodeScanner = projectCodeScanner;
    }

    /**
     * 强制自动补图：扫描工程代码，把缺图位置填成 Pexels 搜索并上传 COS 的真实图片 URL。
     * 只处理图片语义字段的空值，不动 emoji/渐变/表单占位之外的页面结构。
     *
     * @param type        代码生成类型
     * @param appId       应用 id
     * @param userMessage 用户需求（用于提取搜图关键词）
     */
    public void autoFill(CodeGenTypeEnum type, Long appId, String userMessage) {
        try {
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            File root = new File(projectDir);
            if (!root.isDirectory()) {
                return;
            }
            List<File> codeFiles = projectCodeScanner.collectCodeFiles(root);
            String query = extractImageQuery(userMessage);
            int filledCount = 0;
            boolean injectedEmojiImg = false;

            for (File file : codeFiles) {
                if (filledCount >= MAX_FILL) {
                    break;
                }
                String path = projectCodeScanner.relativePath(root, file);
                String content = FileUtil.readUtf8String(file);
                String newContent = fillImagesInContent(content, path, appId, query, filledCount, MAX_FILL);
                if (newContent != null && !newContent.equals(content)) {
                    // 出现了内嵌 <img class="autofill-img">（emoji/icon 字段被替换），需要补 CSS
                    if (newContent.contains("autofill-img")) {
                        injectedEmojiImg = true;
                    }
                    codeFileWriteService.saveProjectFile(type, appId, path, newContent);
                    // 重新统计已补图数（按 COS key 前缀计数）
                    filledCount = projectCodeScanner.countCosUrls(projectDir);
                }
            }
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
    private void ensureAutofillCss(CodeGenTypeEnum type, Long appId, File root) {
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
        // ① JS 数据数组里的图片字段：image/img/src/url/cover 为空、占位；emoji/icon 为纯字符
        Matcher m1 = FIELD_PATTERN.matcher(result);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find() && filled[0] < maxFill) {
            String field = m1.group(1).trim().toLowerCase();
            String rawValue = m1.group(3);
            boolean isEmptyValue = rawValue == null || rawValue.isBlank()
                    || "\"\"".equals(rawValue) || "''".equals(rawValue) || "#".equals(rawValue);
            boolean isPlaceholder = rawValue != null
                    && (rawValue.contains("picsum") || rawValue.contains("via.placeholder"));
            boolean isEmojiValue = rawValue != null
                    && (field.contains("emoji") || field.contains("icon"))
                    && !rawValue.startsWith("http");
            if (!isEmptyValue && !isPlaceholder && !isEmojiValue) {
                continue;
            }
            String replacement = siteImageService.fetchOneImage(appId, query);
            if (replacement == null) {
                continue;
            }
            String newValue = (field.contains("emoji") || field.contains("icon"))
                    ? "<img src=\"" + replacement + "\" class=\"autofill-img\" alt=\"\">"
                    : replacement;
            m1.appendReplacement(sb1, Matcher.quoteReplacement(
                    m1.group(1) + m1.group(2) + newValue + m1.group(2)));
            filled[0]++;
        }
        m1.appendTail(sb1);
        result = sb1.toString();

        // ② HTML <img src=""> 空 src
        Matcher m2 = EMPTY_IMG.matcher(result);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find() && filled[0] < maxFill) {
            String replacement = siteImageService.fetchOneImage(appId, query);
            if (replacement == null) {
                continue;
            }
            m2.appendReplacement(sb2, Matcher.quoteReplacement(
                    m2.group(1) + m2.group(2) + replacement + m2.group(2) + m2.group(3)));
            filled[0]++;
        }
        m2.appendTail(sb2);
        result = sb2.toString();

        return filled[0] > alreadyFilled ? result : content;
    }

    /**
     * 从用户需求中提取图库搜索关键词：去掉"生成/一个/页面"等无意义词，保留内容词
     */
    String extractImageQuery(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "beautiful landscape";
        }
        String cleaned = userMessage
                .replaceAll("[，。！？,.!?、\\s]+", " ")
                .replaceAll("(生成|制作|创建|设计|一个|一款|页面|网页|网站|首页|需要|希望|要求|展示|提供|带|用|的|风格|简洁|美观|大气)", " ")
                .trim();
        if (cleaned.isBlank() || cleaned.length() < 2) {
            return "beautiful landscape";
        }
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
}
