package com.mofanyi.core;

import com.mofanyi.config.ScreenshotProperties;
import com.mofanyi.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebDriver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染验证集成测试（plan-agent-pipeline P0-4）：真起无头浏览器，验证"图裂 + 横向溢出"能被抓到。
 * 本机没有可用浏览器驱动时通过 Assumptions 跳过，不阻塞构建。
 */
class RenderVerifierTest {

    private static final long APP_ID = 123L;

    @TempDir
    Path tmpRoot;

    private static boolean browserAvailable;
    private static String driverPath;
    private static String browserInitError = "";

    @BeforeAll
    static void checkBrowser() {
        driverPath = resolveDriverPath();
        WebDriver driver = null;
        try {
            driver = BrowserFactory.create("edge", driverPath, true, 800, 600);
            browserAvailable = driver != null;
        } catch (Throwable t) {
            browserAvailable = false;
            browserInitError = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (driver != null) {
                try {
                    driver.quit();
                } catch (Exception ignore) {
                    // 忽略
                }
            }
        }
    }

    /**
     * 定位本机 msedgedriver：优先环境变量，其次 Selenium 缓存目录。
     * 找不到时返回 null，测试会通过 Assumptions 跳过（不阻塞构建）。
     */
    private static String resolveDriverPath() {
        String fromEnv = System.getenv("SCREENSHOT_DRIVER_PATH");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path cache = Paths.get(System.getProperty("user.home"), ".cache", "selenium", "msedgedriver");
        if (!Files.isDirectory(cache)) {
            return null;
        }
        try (Stream<Path> stream = Files.walk(cache)) {
            return stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("msedgedriver.exe"))
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private RenderVerifier newVerifier(CodeFileWriteService files) {
        ScreenshotProperties props = new ScreenshotProperties();
        props.setBrowser("edge");
        props.setDriverPath(driverPath);
        return new RenderVerifier(props, files);
    }

    @Test
    @DisplayName("渲染验证能抓到图裂与横向溢出")
    void detectsBrokenImageAndOverflow() {
        Assumptions.assumeTrue(browserAvailable,
                "本机无可用浏览器驱动，跳过渲染验证集成测试。" + browserInitError);

        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tmpRoot.toString());
        files.saveProjectFile(CodeGenTypeEnum.HTML, APP_ID, "index.html",
                "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
                        + "<style>body{margin:0}.wide{width:2000px;height:40px;background:#333}</style>"
                        + "</head><body>"
                        + "<h1>渲染验证集成测试页面</h1>"
                        + "<img src='file:///definitely/not/exist/broken.png' alt='broken'>"
                        + "<div class='wide'></div>"
                        + "</body></html>");

        RenderVerifier verifier = newVerifier(files);
        RenderVerifier.RenderReport report = verifier.verify(CodeGenTypeEnum.HTML, APP_ID);

        Assumptions.assumeTrue(report.isUsable(), "浏览器不可用，跳过断言");
        assertTrue(report.hasProblems(), "应检出渲染问题");
        assertTrue(report.viewports().stream().anyMatch(v -> !v.brokenImages().isEmpty()),
                "应检出图裂");
        assertTrue(report.viewports().stream().anyMatch(RenderVerifier.ViewportResult::overflow),
                "应检出横向溢出（2000px 宽内容）");
        assertTrue(report.toPromptText().contains("图裂"), "喂给修复 agent 的文本应包含图裂描述");
    }

    @Test
    @DisplayName("正常页面不应报问题")
    void healthyPageHasNoProblems() {
        Assumptions.assumeTrue(browserAvailable,
                "本机无可用浏览器驱动，跳过渲染验证集成测试。" + browserInitError);

        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tmpRoot.toString());
        files.saveProjectFile(CodeGenTypeEnum.HTML, APP_ID, "index.html",
                "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
                        + "<style>body{margin:0;font-family:sans-serif}</style></head><body>"
                        + "<h1>一个正常渲染的页面</h1><p>内容足够多，不会被判为空白页。</p>"
                        + "</body></html>");

        RenderVerifier verifier = newVerifier(files);
        RenderVerifier.RenderReport report = verifier.verify(CodeGenTypeEnum.HTML, APP_ID);

        Assumptions.assumeTrue(report.isUsable(), "浏览器不可用，跳过断言");
        assertFalse(report.hasProblems(), "正常页面不应被判定有问题：" + report.toPromptText());
    }

    @Test
    @DisplayName("Vue ES module 构建产物必须通过 HTTP 源加载")
    void loadsVueDistOverHttp() {
        Assumptions.assumeTrue(browserAvailable,
                "本机无可用浏览器驱动，跳过渲染验证集成测试。" + browserInitError);

        CodeFileWriteService files = new CodeFileWriteService();
        files.setTmpRoot(tmpRoot.toString());
        files.saveProjectFile(CodeGenTypeEnum.VUE, APP_ID, "dist/index.html",
                "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>"
                        + "<link rel='stylesheet' href='./assets/app.css'></head><body>"
                        + "<div id='app'></div>"
                        + "<script type='module' src='./assets/app.js'></script>"
                        + "</body></html>");
        files.saveProjectFile(CodeGenTypeEnum.VUE, APP_ID, "dist/assets/app.css",
                "#app{padding:2rem;font-family:sans-serif}");
        files.saveProjectFile(CodeGenTypeEnum.VUE, APP_ID, "dist/assets/app.js",
                "document.querySelector('#app').textContent = '"
                        + "Vue 构建产物已通过临时 HTTP 源正常加载并完成渲染';");

        RenderVerifier verifier = newVerifier(files);
        RenderVerifier.RenderReport report = verifier.verify(CodeGenTypeEnum.VUE, APP_ID);

        Assumptions.assumeTrue(report.isUsable(), "浏览器不可用，跳过断言");
        assertFalse(report.hasProblems(), "Vue dist 不应因 file:// 跨源限制被误判："
                + report.toPromptText());
        assertTrue(report.viewports().stream().allMatch(v -> v.textLength() > 20),
                "三档视口都应渲染出页面文本");
    }
}
