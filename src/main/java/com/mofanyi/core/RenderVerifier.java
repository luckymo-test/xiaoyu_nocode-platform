package com.mofanyi.core;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mofanyi.config.ScreenshotProperties;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 交付前渲染验证（plan-agent-pipeline.md P0-4）。
 *
 * <p>生成完成后用无头浏览器按 <b>3 档视口</b>（桌面 1440 / 平板 768 / 手机 375）真实渲染
 * 生成的 {@code index.html}，采集结构化问题：
 * <ul>
 *   <li>图裂：{@code img.complete && naturalWidth === 0}、空 src、占位图</li>
 *   <li>横向溢出：{@code scrollingElement.scrollWidth > clientWidth}</li>
 *   <li>空白页：body 可见文本过少</li>
 *   <li>控制台严重错误（驱动支持时采集）</li>
 * </ul>
 * 报告落盘到 {@code .agent/verify.json} 供追溯，同时转成文本喂给修复 agent。
 *
 * <p>范围：html / multi_file（vue 预览走 dist 构建产物，暂不在此验证）。
 * 局限：通过 {@code file://} 加载，ES module / fetch 这类需要 http 源的页面可能加载失败。
 */
@Slf4j
@Service
public class RenderVerifier {

    /**
     * 3 档视口
     */
    private static final List<Viewport> VIEWPORTS = List.of(
            new Viewport("桌面", "desktop", 1440, 900),
            new Viewport("平板", "tablet", 768, 900),
            new Viewport("手机", "mobile", 375, 800));

    /**
     * body 可见文本低于此长度视为"疑似空白页"
     */
    private static final int MIN_TEXT_LENGTH = 20;

    /**
     * 控制台错误最多采集条数
     */
    private static final int MAX_CONSOLE_ERRORS = 10;

    /**
     * 页面加载后等待渲染/图片就绪的毫秒数
     */
    private static final long SETTLE_MILLIS = 1200;

    /**
     * 页面内取证脚本：返回 JSON
     */
    private static final String COLLECT_JS =
            "return (function () {"
                    + "  var broken = [];"
                    + "  var imgs = document.images || [];"
                    + "  for (var i = 0; i < imgs.length; i++) {"
                    + "    var im = imgs[i];"
                    + "    var src = im.getAttribute('src') || '';"
                    + "    if (!src.trim() || (im.complete && im.naturalWidth === 0)) {"
                    + "      broken.push((im.currentSrc || src || '(空 src)').slice(0, 200));"
                    + "    }"
                    + "  }"
                    + "  var se = document.scrollingElement || document.documentElement;"
                    + "  var body = document.body;"
                    + "  var text = body ? (body.innerText || '').trim() : '';"
                    + "  return JSON.stringify({brokenImages: broken,"
                    + "    overflow: (se.scrollWidth - se.clientWidth) > 1,"
                    + "    scrollWidth: se.scrollWidth, clientWidth: se.clientWidth,"
                    + "    textLength: text.length});"
                    + "})();";

    private final ScreenshotProperties screenshotProperties;
    private final CodeFileWriteService codeFileWriteService;

    public RenderVerifier(ScreenshotProperties screenshotProperties, CodeFileWriteService codeFileWriteService) {
        this.screenshotProperties = screenshotProperties;
        this.codeFileWriteService = codeFileWriteService;
    }

    private record Viewport(String label, String name, int width, int height) {
    }

    /**
     * 单个视口的渲染结果
     */
    public record ViewportResult(String name, String label, int width, List<String> brokenImages,
                                 boolean overflow, int scrollWidth, int clientWidth,
                                 int textLength, List<String> consoleErrors) {

        public boolean hasProblem() {
            return !brokenImages.isEmpty() || overflow || textLength < MIN_TEXT_LENGTH
                    || !consoleErrors.isEmpty();
        }
    }

    /**
     * 渲染验证报告
     */
    public record RenderReport(List<ViewportResult> viewports, List<String> errors) {

        /**
         * 是否真的跑出了结果（浏览器起不来时为 false，调用方应跳过修复回路）
         */
        public boolean isUsable() {
            return viewports != null && !viewports.isEmpty();
        }

        public boolean hasProblems() {
            return viewports != null && viewports.stream().anyMatch(ViewportResult::hasProblem);
        }

        /**
         * 转成喂给修复 agent 的文本（只列有问题的视口）
         */
        public String toPromptText() {
            if (viewports == null || viewports.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ViewportResult v : viewports) {
                if (!v.hasProblem()) {
                    continue;
                }
                count++;
                sb.append("- ").append(v.label()).append("（").append(v.width()).append("px 宽）：");
                List<String> parts = new ArrayList<>();
                if (!v.brokenImages().isEmpty()) {
                    parts.add("图裂/空图 " + v.brokenImages().size() + " 处 → " + String.join(" | ", v.brokenImages()));
                }
                if (v.overflow()) {
                    parts.add("横向溢出（内容宽 " + v.scrollWidth() + "px > 视口 " + v.clientWidth() + "px）");
                }
                if (v.textLength() < MIN_TEXT_LENGTH) {
                    parts.add("页面几乎空白（可见文本仅 " + v.textLength() + " 字符）");
                }
                if (!v.consoleErrors().isEmpty()) {
                    parts.add("控制台报错 " + v.consoleErrors().size() + " 条 → " + String.join(" | ", v.consoleErrors()));
                }
                sb.append(String.join("；", parts)).append("\n");
            }
            if (count == 0) {
                return "";
            }
            return "渲染验证发现 " + count + " 个视口存在问题：\n" + sb;
        }

        /**
         * 一行摘要（日志用）
         */
        public String summary() {
            if (viewports == null || viewports.isEmpty()) {
                return "未产出结果";
            }
            long bad = viewports.stream().filter(ViewportResult::hasProblem).count();
            return bad == 0
                    ? "3 档视口全部通过"
                    : bad + "/" + viewports.size() + " 档视口存在问题";
        }
    }

    /**
     * 渲染验证：逐视口渲染并采集问题。浏览器不可用时返回 report.isUsable()==false。
     */
    public RenderReport verify(CodeGenTypeEnum type, Long appId) {
        List<ViewportResult> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (type == null || appId == null || appId <= 0) {
            return new RenderReport(results, List.of("参数非法"));
        }
        String projectDir = codeFileWriteService.getProjectDir(type, appId);
        File index = Paths.get(projectDir, "index.html").toFile();
        if (!FileUtil.exist(index)) {
            return new RenderReport(results, List.of("未找到 index.html，跳过渲染验证"));
        }
        String url = index.toURI().toString();
        WebDriver driver = null;
        try {
            // 只起一个会话，逐视口 resize——比每档各起一次会话快得多
            driver = createDriver(VIEWPORTS.get(0));
            driver.get(url);
            waitReady(driver);
            for (Viewport vp : VIEWPORTS) {
                try {
                    driver.manage().window().setSize(new Dimension(vp.width(), vp.height()));
                    Thread.sleep(SETTLE_MILLIS);
                    results.add(inspect(driver, vp));
                } catch (Exception e) {
                    log.warn("[verify] 视口 {} 渲染失败，appId={}", vp.name(), appId, e);
                    errors.add("视口 " + vp.label() + " 渲染失败：" + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[verify] 浏览器不可用，跳过渲染验证，appId={}", appId, e);
            errors.add("浏览器不可用：" + e.getMessage());
        } finally {
            quit(driver);
        }
        RenderReport report = new RenderReport(results, errors);
        log.info("[verify] appId={} 渲染验证完成：{}", appId, report.summary());
        return report;
    }

    /**
     * 报告落盘到 {@code .agent/verify-{stage}.json}（阶段产物，可追溯）。
     * 分 stage 落盘（before / after）避免"修复后的结果覆盖掉修复前的问题清单"。
     *
     * @param stage 阶段标识，如 before / after
     */
    public void persistReport(CodeGenTypeEnum type, Long appId, RenderReport report, String stage) {
        if (report == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.set("generatedAt", java.time.LocalDateTime.now().toString());
            root.set("hasProblems", report.hasProblems());
            JSONArray arr = new JSONArray();
            if (report.viewports() != null) {
                for (ViewportResult v : report.viewports()) {
                    JSONObject o = new JSONObject();
                    o.set("viewport", v.name());
                    o.set("width", v.width());
                    o.set("brokenImages", v.brokenImages());
                    o.set("overflow", v.overflow());
                    o.set("scrollWidth", v.scrollWidth());
                    o.set("clientWidth", v.clientWidth());
                    o.set("textLength", v.textLength());
                    o.set("consoleErrors", v.consoleErrors());
                    arr.add(o);
                }
            }
            root.set("viewports", arr);
            root.set("errors", report.errors());
            String projectDir = codeFileWriteService.getProjectDir(type, appId);
            File out = Paths.get(projectDir, ".agent", "verify-" + stage + ".json").toFile();
            FileUtil.mkParentDirs(out);
            FileUtil.writeString(root.toStringPretty(), out, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            log.warn("[verify] 报告落盘失败，appId={}", appId, e);
        }
    }

    private WebDriver createDriver(Viewport vp) {
        String browser = screenshotProperties.getBrowser();
        String driverPath = screenshotProperties.getDriverPath();
        try {
            return BrowserFactory.create(browser, driverPath, true, vp.width(), vp.height(), true);
        } catch (RuntimeException e) {
            // 开启控制台日志的会话创建失败时降级重试（部分驱动不支持 loggingPrefs）
            log.warn("[verify] 带日志能力的会话创建失败，降级重试：{}", e.getMessage());
            return BrowserFactory.create(browser, driverPath, true, vp.width(), vp.height(), false);
        }
    }

    private void waitReady(WebDriver driver) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(10))
                    .until(d -> "complete".equals(((JavascriptExecutor) d)
                            .executeScript("return document.readyState")));
        } catch (Exception ignore) {
            // 加载超时不阻塞取证
        }
    }

    private ViewportResult inspect(WebDriver driver, Viewport vp) {
        Object raw = ((JavascriptExecutor) driver).executeScript(COLLECT_JS);
        JSONObject obj = toJsonObject(raw);
        List<String> broken = new ArrayList<>();
        JSONArray arr = obj.getJSONArray("brokenImages");
        if (arr != null) {
            for (Object o : arr) {
                broken.add(String.valueOf(o));
            }
        }
        return new ViewportResult(
                vp.name(), vp.label(), vp.width(), broken,
                obj.getBool("overflow", false),
                obj.getInt("scrollWidth", 0),
                obj.getInt("clientWidth", 0),
                obj.getInt("textLength", 0),
                collectConsoleErrors(driver));
    }

    /**
     * 归一化取证脚本的返回值：Chromium 可能返回 JSON 字符串，也可能返回 Map（取决于驱动实现）。
     */
    private JSONObject toJsonObject(Object raw) {
        if (raw == null) {
            throw new IllegalStateException("取证脚本返回 null");
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            return new JSONObject(map);
        }
        String text = String.valueOf(raw);
        if (!text.trim().startsWith("{")) {
            throw new IllegalStateException("取证脚本返回非 JSON：" + StrUtil.maxLength(text, 200));
        }
        return JSONUtil.parseObj(text);
    }

    /**
     * 采集控制台严重错误；驱动/会话不支持时返回空列表（不阻塞验证）
     */
    private List<String> collectConsoleErrors(WebDriver driver) {
        List<String> errors = new ArrayList<>();
        try {
            LogEntries entries = driver.manage().logs().get(LogType.BROWSER);
            for (LogEntry entry : entries) {
                if (entry.getLevel() == java.util.logging.Level.SEVERE) {
                    errors.add(StrUtil.maxLength(entry.getMessage(), 200));
                    if (errors.size() >= MAX_CONSOLE_ERRORS) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            // 该驱动不支持浏览器日志，忽略
        }
        return errors;
    }

    private void quit(WebDriver driver) {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (Exception ignore) {
            // 忽略关闭异常
        }
    }
}
